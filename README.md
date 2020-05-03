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

