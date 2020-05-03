# jsonapi
Proposal implementation for a Light-Weight JSON API (JEP 198)

## How the API is built ?

So first the basic idea is to provide a couple of interfaces (ObjectVisitor and ArrayVisitor)
to be able to generate events from a JSON document, modify them using some visitors
and write them in a file or in memory

```
    JSON reader --> JSON visitor --> JSON writer
```

It's not a new idea, it's how jackson-code works, or ASM or any API based on visitor.

### Design of the visitors

The JSON spec says there are 3 kinds of JSON values: objects, arrays and primitive values
(boolean, numbers, strings, etc).
So for an array, a basic visitor has 5 methods,
- `visitStart()`` and `visitEnd()``, because objects and arrays use symbols to start and end
  (curly braces and option bracket)
- `visitObject()`
- `visitArray()`
- `visitValue(JsonValue value)`

Jackson uses a lot of variants of visitValue (visitValueInt, visitValueString, etc) to avoid
boxing primitive types, i'm using an inline type, JsonValue, to avoid the boxing and keep
the API sane.

For an object, array, object and values are prefix by the member name (the name before the ':')
so the ObjectVisitor has the same methods but visitObject, visitArray and visitValue takes
a string as first argument
- `visitMemberObject(String member)`
- `visitMemberArray(String member)`
- `visitMemberValue(String member, JsonValue value)`

The JSON reader have two methods `parse(java.io.Reader, ObjectVisitor)` and
`parse(java.io.Reader, ArrayVisitor)` depending if the JSON document starts with an object
or an array and a JSON writer implements both visitors a code like this do a copy from
an IO reader to an IO writer
```
  JsonReader.parse(reader, new JsonWriter(writer))
```

Because we want a class to be able to implement both interfaces, the methods `visitStart`
(resp. `visitEnd`) have to be renamed to `visitStartArray` and `visitStartObject` to avoid
collision.


### Fast forward on the JSON document

Modern application tends to send fat JSON to avoid the client to do several round trips
to have the data they need. So some times, part of the JSON document is useless for
the application.
So we want the visitor to have more control on the parsing and be able to say that an object
or an array can be skipped.

So the API of the visitors is modified so `visitObject` (resp. `visitArray`) returns
an ObjectVisitor that can be null or not. If null, the parsing of the JSON object is
skipped, otherwise, the newly returned visitor is used to receive the JON event of
the parsing.

So the signature for the method are
- `ObjectVisitor ObjectVisitor.visitMemberObject(String member)`
- `ArrayVisitor ObjectVisitor.visitMemebrArray(String member)`
- `ObjectVisitor ArrayVisitor.visitObject()`
- `ArrayVisitor ArrayVisitor.visitArray()`

This also allows to specify different visitors for different objects/arrays.


### Higher order operations on visitors

Once you have visitors like ObjectVisitor, you can implement high order methods on them
to provide the usual map and filter operation.
So i've introduced the two methods
- `mapName()` which allow to change the name of the member of the objects
- `filterName()` that allow to discard array, object and value depending on the member name
   of the object.

One thing interesting with `filterName()` is that if the predicate return false for an array
or an object, the corresponding visitor methods `visitMemberArray(String)` and
`visitMemberObject(String)` return null, so the content of the array/object is actually
skipped by the JsonReader.


### Design of the builders

We now want to be able to create a list/map of objects from a JSON array/object
A builder is a class implementing ArrayVisitor that stores all the values
into a java.util.List (resp a class implementing ObjectVisitor using a java.util.Map).

Here, i've chosen to not go the the route of introducing two new classes JsonArray and JsonObject
for several reasons
- I want to separate the notion of builders, the class implementing ArrayVisitor acts as a builder
  and the result of the parsing which i believe should be immutable.
- In the code, those classes tends to be in place you don't want them, they should be at the
  peripheral of the application like any IO related classes but ends up in the core part of the
  application.
- Fundamentally, JSON is an untyped format, having dedicated Java object representing
  a JSON object and a JSON array means introducing classes that throws a lot of CCE in the JDK.
  By example, a method like  `JsonObject.getInt(String member)` may fail with a ClassCastException

So i've decided to separate the builder itself from the result of the parsing,
`ObjectVisitor.visitEndObject()` (resp `ArrayVisitor.visitEndArray()`) have been changed
to return an object which is the result of the parsing.

I've also introduce the idea of a post transformation operation which is executed during
the visitEnd, so the implementation by default calls Collections.unmodifiableMap/List
and this return an unmodifiable map/list by default

  ```
  Map<String, Object> map = JsonReader.parse(reader, new ObjectBuilder());
  List<Object> list = JsonReader.parse(reader, new ArrayBuilder());
  ```

The exact implementation used by the builder, LinkedHashMap and ArrayList by default,
are defined in the class BuilderConfig because the builders are recursively defined,
an ObjectBuilder will itself spin another ObjectBuilder if the first builder visit an Object.
The BuilderConfig is defined by 4 functions, the supplier of Map, the supplier of List,
the operations (Map -> Map and List -> List to applied at the end of the visit.

By example, to create an ArrayVisitor that will produce an immutable list
```
  var builder = new BuilderConfig(
        HashMap::new, Map::copyOf, ArrayList::new, List::copyOf)
      .newArrayBuilder()
```

### Builders as source of events

A builder can also but used to generate a JSON document, for that both builders has methods
`add()` to add any object to the builder.
This can be problematic because the added object can be an object that can not be mapped to
a JSON value, in that case, it is considered as an 'opaque value' by the class JsonValue,
and can be stored as a JsonValue with no alteration.
In the end, it will be generated as a JSON string by calling toString() on the object.

ObjectBuilder also defines two specific methods `withObject` and `withObject` that
allows to define a sub-array/sub-object in a fluent way
By example
```
ObjectBuilder builder = new ObjectBuilder()
    .add("name", "Franky")
    .withObject("address", b -> b
        .add("street", "3rd")
        .add("city", "NY"))
    .add("age", 45);
```

Each builder also provides a method `replay(visitor)` that acts as a producer of JSON events
that will be sent to the visitor taken as parameter, so generating the content of a builder
in JSON format is as easy as
```
ObjectBuilder builder = ...
builder.replay(new JSonWriter(writer));
```


### The Stream API

One requirement is to has an API that works with java.util.stream.Stream, while it's great
in term of user API
```
  Stream<Object> stream = JsonReader.stream(reader, visitor);
```
this requires some API massaging.

Unlike the classical use of the API where the JsonReader pushes the JSON value to the visitors,
with a stream based API, it's the stream, exactly the Spliterator, that controls when/if a stream
item is necessary.

I've started first with a new StreamVisitor that works that way. The Stream ask for an item,
the parser parse the next JSON token, it calls the StreamVisitor right visit method depending
on the type of value (array, object, primitive value), these method produces a value
which is sent to the Stream.
If the stream read less array items than available, the remaining items are skipped.

In term or API, a StreamVisitor is really like an ArrayVisitor
for array and object, it returns the value returned by the method visitEnd of the created
object/array. They are two differences, the return value of the visitEnd of the visitor itself
(not the one of the visitors it spins) is useless and perhaps more importantly visitValue now
needs to return a value.

Because both visitors (ObjectVisitor and ArrayVisitor) references each other, introducing a third
visitor doesn't work well. I've also played with an implementation making the StreamVisitor
a subtype of ArrayVisitor but it requires to dynamically test the kind of array visitor
in the event producers (JsonReader or builder methods `replay()`) that is ugly.

I end up modifying ArrayVisitor API to
- introduce the concept of API mode, a visitor declares to be either in push mode (classical mode)
  or pull mode (stream mode)
- change `visitValue(JsonValue)` and `visitMemberValue(String, JsonValue)` to return an Object,
  the return value is ignored in push mode and injected into the Stream in pull mode.
- change `visitStart()` to return a value indicating the api mode by return an enum
  VisitorMode (while this change is not strictly necessary, it avoid the surprise of
  having the a visitor used in wrong place, by example a builder used with a Stream)


### Internal streaming

The API above solve the solution of starting the parsing with a Stream but not when right
in the middle of the parsing (so in push mode), someone want to create a Stream on an array.
Given that it's an array, the reader will call visitArray, so here the idea is to allow
to return a kind of ArrayVisitor that creates a Stream.
Again, it can be a subtype of ArrayVisitor but i end up (ab-) using of the VisitorMode
introduced previously, by
- introducing a new mode named, pull inside
- add a method visitStream in ArrayVisitor that takes a function that takes a Stream and
  return a value, this method is declared as a default method and throw an exception by default

It works that way, when the visitor method `visitStartArray()` return PULL_INSIDE,
any JSON event producers that support this mode (like JsonReader) should calls
`visitStream()` with a stream as argument, when the stream is used, it will calls the method
`visitArray()`, `visitObject()` and `visitValue()` in pull mode.
Again, if the stream read less array items than available, the remaining items are skipped.

Note: with this way, the ArrayBuilder is less relevant because unlike with an ArrayBuilder
which is only able to produce lists, the ArrayVisitor in 'pull inside' mode uses
the Stream API which is backed by the full power of the collector API.


### Leveling up

While working with builders is cool, the way of JSON works is great for an untyped language
like JavaScript but it doesn't map well to Java, mostly because in Java you don't represen
 objects with Map but with real objects.

At the same time, writing a full mapping library is out of scope because the semantics of
Java classes can very complex. But i think there is a sweet spot, here, allow only the mapping
of enums and records which are the two forms of classes with a constrained semantics
(you hardcoded logic for them in the Jav serialization).

So i've introduced the Binder, that let you read and write records, records of records,
list of records, etc.
```
record Point(int x, int y) { }
List<Point> list = List.of(new Point(4, 5), new Point(0, 2));
Binder binder = new Binder(lookup());
// write in a String
String text = binder.write(list);
// read from a String
List<Point> points = binder.read(text, Point.class, Binder.IN_ARRAY);
// or using a Stream
Stream<Point> stream = binder.stream(text, Point.class);
```

Writing records/enums is not an issue because you have the runtime type information provided
by getClass(). Reading is a bit more challenging because you have to guide the decoding
by providing the type information.
I've introduce a type named Spec that represent the static type information that are used
when decoding.

Spec is an opaque type, you can construct one but not see how it is implemented to avoid
to leak too many details.
There are 3 static factories to create specs
- Spec.newTypedValue() to create a description of a primitive value
- Spec.newTypedObject() to create a description of an object
- Spec.newTypedArray() to create a description of an array

You have several high order operations on spec
- convert() which allow to specify the function to execute to transform a JSON primitive
  value to Java type and vice-versa
- filterName(), to ignore some members of an object (this object can not be written)
- stream(), to create an object from a JSON seen as a Stream
- array(), to create a List of the object represented by the spec
- object(), to create a Map of the object represented by the spec

The last two are just convenient methods on top of `newTypedArray()` and `newTypedObject`

The Binder is extensible, your can register spec finders (SpecFinder), a function that
for a Class return an Optional<Spec>, the binder iterates on them in register order until one
answer with a non empty Optional.
By default, you have two of them are already registered, one for records and one for enums.

The idea is that because it's cheap to create records from a user point of view,
the binder doesn't have to have all these intricate details to deserialize a JSON fragment
into a domain model but be more like an intermediary phase, the record instance being used
to update the model.

Adding annotations and creates a SpecFinder that translates those annotations into
higher functions on the spec instances is left as an exercise to the reader :)
