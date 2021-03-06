package fr.umlv.jsonapi.bind;

import static java.util.Objects.requireNonNull;

import fr.umlv.jsonapi.ArrayVisitor;
import fr.umlv.jsonapi.JsonValue;
import fr.umlv.jsonapi.ObjectVisitor;
import fr.umlv.jsonapi.bind.Binder.BindingException;
import fr.umlv.jsonapi.internal.RootVisitor;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

final class Specs {
  private Specs() {
    throw new AssertionError();
  }

  record ValueSpec(String name, Object defaultValue, Converter converter) implements Spec {
    @Override
    public String toString() {
      return name;
    }

    private void replayValue(Object value, ArrayVisitor visitor) {
      var jsonValue = JsonValue.fromAny(value);
      visitor.visitValue(convertFrom(jsonValue));
    }
    private void replayMember(String name, Object value, ObjectVisitor visitor) {
      var jsonValue = JsonValue.fromAny(value);
      visitor.visitMemberValue(name, convertFrom(jsonValue));
    }

    private JsonValue convertFrom(JsonValue value) {
      if (converter == null) {
        return value;
      }
      return converter.convertFrom(value);
    }

    private JsonValue convertTo(JsonValue jsonValue) {
      if (converter == null) {
        return jsonValue;
      }
      return converter.convertTo(jsonValue);
    }

    public Spec convertWith(Converter converter) {
      var currentConverter = this.converter;
      var newConverter = (currentConverter == null)?
          converter:
          new Converter() {
            @Override
            public JsonValue convertTo(JsonValue value) {
              return converter.convertTo(currentConverter.convertTo(value));
            }
            @Override
            public JsonValue convertFrom(JsonValue object) {
              return currentConverter.convertFrom(converter.convertFrom(object));
            }
          };
      var convertedDefaultValue = newConverter.convertTo(JsonValue.fromAny(defaultValue)).asObject();
      return new ValueSpec(name + ".convert()", convertedDefaultValue, newConverter);
    }
  }

  record StreamSpec(Spec component, Function<? super Stream<Object>, ?> aggregator) implements Spec {
    @Override
    public String toString() {
      return component + ".stream(aggregator)";
    }

    ObjectVisitor newObjectFrom() {
      if (component instanceof ObjectSpec objectSpec) {
        return new BindObjectVisitor(objectSpec);
      }
      throw new BindingException("invalid component spec for an object " + component);
    }
    ArrayVisitor newArrayFrom() {
      if (component instanceof ArraySpec arraySpec) {
        return new BindArrayVisitor(arraySpec);
      }
      if (component instanceof StreamSpec streamSpec) {
        return new BindStreamVisitor(streamSpec);
      }
      throw new BindingException("invalid component spec for an array " + component);
    }
  }

  record ObjectSpec(String name, Predicate<? super String> filter, ObjectLayout<?>objectLayout) implements Spec {
    @Override
    public String toString() {
      return name;
    }

    ObjectVisitor newMemberObject(String name, Consumer<Object> postOp) {
      var spec = objectLayout.memberSpec(name);
      if (spec instanceof ObjectSpec objectSpec) {
        return new BindObjectVisitor(objectSpec, postOp);
      }
      throw new BindingException("invalid component spec for an object " + spec + " for element " + name);
    }
    ArrayVisitor newMemberArray(String name, Consumer<Object> postOp) {
      var spec = objectLayout.memberSpec(name);
      if (spec instanceof ArraySpec arraySpec) {
        return new BindArrayVisitor(arraySpec, postOp);
      }
      if (spec instanceof StreamSpec streamSpec) {
        return new BindStreamVisitor(streamSpec, postOp);
      }
      throw new BindingException("invalid component spec for an array " + spec + " for element " + name);
    }

    Object replay(Object value, Binder binder, ObjectVisitor objectVisitor) {
      if (filter != null) {
        throw new BindingException("can not replay a filtered spec");
      }
      objectVisitor.visitStartObject();
      objectLayout.replay(value, (name, elementValue) -> replayMember(name, elementValue, binder, objectVisitor));
      return objectVisitor.visitEndObject();
    }

    public ObjectSpec filterWith(Predicate<? super String> predicate) {
       var filter = this.filter;
       Predicate<? super String> newFilter = (filter == null)? predicate: name -> predicate.test(name) && filter.test(name);
       return new ObjectSpec(name + ".filter()", newFilter, objectLayout);
    }
  }

  record ArraySpec(Spec component, ArrayLayout<?> arrayLayout) implements Spec {
    @Override
    public String toString() {
      return component + ".array()";
    }

    ObjectVisitor newObject(Consumer<Object> postOp) {
      if (component instanceof ObjectSpec objectSpec) {
        return new BindObjectVisitor(objectSpec, postOp);
      }
      throw new BindingException("invalid component spec for an object " + component);
    }
    ArrayVisitor newArray(Consumer<Object> postOp) {
      if (component instanceof ArraySpec arraySpec) {
        return new BindArrayVisitor(arraySpec, postOp);
      }
      if (component instanceof StreamSpec streamSpec) {
        return new BindStreamVisitor(streamSpec, postOp);
      }
      throw new BindingException("invalid component spec for an array " + component);
    }

    /*Object accept(Object value, Binder binder, ArrayVisitor arrayVisitor) {
      arrayVisitor.visitStartObject();
      arrayLayout.accept(value, (name, elementValue) -> acceptValue(elementValue, binder, arrayVisitor));
      return arrayVisitor.visitEndObject();
    }*/
  }

  static JsonValue convert(ArraySpec spec, JsonValue value) {
    var elementSpec = spec.component;
    if (elementSpec instanceof ValueSpec valueSpec) {
      return valueSpec.convertTo(value);
    }
    throw new BindingException(spec + " can not convert " + value + " to " + elementSpec);
  }
  static JsonValue convert(StreamSpec spec, JsonValue value) {
    var elementSpec = spec.component;
    if (elementSpec instanceof ValueSpec valueSpec) {
      return valueSpec.convertTo(value);
    }
    throw new BindingException(spec + " can not convert " + value + " to " + elementSpec);
  }
  static JsonValue convert(ObjectSpec spec, String name, JsonValue value) {
    var elementSpec = spec.objectLayout.memberSpec(name);
    if (elementSpec instanceof ValueSpec valueSpec) {
      return valueSpec.convertTo(value);
    }
    throw new BindingException(spec + "." + name + " can not convert " + value + " to " + elementSpec);
  }


  static Object defaultValue(Spec spec) {
    if (spec instanceof ValueSpec valueSpec) {
      return valueSpec.defaultValue();
    }
    return null;
  }


  static Object replayRoot(Object value, Binder binder, RootVisitor visitor) {
    requireNonNull(value);  // help the JIT :)
    if (value instanceof Iterable<?> iterable) {
      var arrayVisitor = visitor.visitArray();
      if (arrayVisitor == null) {
        return null;
      }
      return replayIterable(iterable, binder, arrayVisitor);
    }
    if (value instanceof Iterator<?> iterator) {
      var arrayVisitor = visitor.visitArray();
      if (arrayVisitor == null) {
        return null;
      }
      return replayIterator(iterator, binder, arrayVisitor);
    }
    if (value instanceof Stream<?> stream) {
      var arrayVisitor = visitor.visitArray();
      if (arrayVisitor == null) {
        return null;
      }
      return replayStream(stream, binder, arrayVisitor);
    }
    if (value instanceof Map<?, ?> map) {
      var objectVisitor = visitor.visitObject();
      if (objectVisitor == null) {
        return null;
      }
      return replayMap(map, binder, objectVisitor);
    }
    var spec = binder.spec(value.getClass());
    if (spec instanceof ObjectSpec objectSpec) {
      var objectVisitor = visitor.visitObject();
      if (objectVisitor == null) {
        return null;
      }
      return objectSpec.replay(value, binder, objectVisitor);
    }
    throw new BindingException("can not accept " + value + " of spec " + spec);
  }

  static void replayValue(Object value, Binder binder, ArrayVisitor visitor) {
    if (value == null) {
      visitor.visitValue(JsonValue.nullValue());
      return;
    }
    if (value instanceof Iterable<?> list) {
      var arrayVisitor = visitor.visitArray();
      if (arrayVisitor != null) {
        replayIterable(list, binder, arrayVisitor);
      }
      return;
    }
    if (value instanceof Iterator<?> iterator) {
      var arrayVisitor = visitor.visitArray();
      if (arrayVisitor != null) {
        replayIterator(iterator, binder, arrayVisitor);
      }
      return;
    }
    if (value instanceof Stream<?> stream) {
      var arrayVisitor = visitor.visitArray();
      if (arrayVisitor != null) {
        replayStream(stream, binder, arrayVisitor);
      }
      return;
    }
    if (value instanceof Map<?,?> map) {
      var objectVisitor = visitor.visitObject();
      if (objectVisitor != null) {
        replayMap(map, binder, objectVisitor);
      }
      return;
    }
    if (value instanceof Optional<?> optional) {
      optional.ifPresent(v -> visitor.visitValue(JsonValue.fromAny(v)));
      return;
    }
    var spec = binder.spec(value.getClass());
    if (spec instanceof ObjectSpec objectSpec) {
      var objectVisitor = visitor.visitObject();
      if (objectVisitor != null) {
        objectSpec.replay(value, binder, objectVisitor);
      }
      return;
    }
    if (spec instanceof ValueSpec valueSpec) {
      valueSpec.replayValue(value, visitor);
      return;
    }
    visitor.visitValue(JsonValue.fromAny(value));
  }

  static void replayMember(String name, Object value, Binder binder, ObjectVisitor visitor) {
    if (value == null) {
      visitor.visitMemberValue(name, JsonValue.nullValue());
      return;
    }
    if (value instanceof Iterable<?> list) {
      var arrayVisitor = visitor.visitMemberArray(name);
      if (arrayVisitor != null) {
        replayIterable(list, binder, arrayVisitor);
      }
      return;
    }
    if (value instanceof Iterator<?> iterator) {
      var arrayVisitor = visitor.visitMemberArray(name);
      if (arrayVisitor != null) {
        replayIterator(iterator, binder, arrayVisitor);
      }
      return;
    }
    if (value instanceof Stream<?> stream) {
      var arrayVisitor = visitor.visitMemberArray(name);
      if (arrayVisitor != null) {
        replayStream(stream, binder, arrayVisitor);
      }
      return;
    }
    if (value instanceof Map<?,?> map) {
      var objectVisitor = visitor.visitMemberObject(name);
      if (objectVisitor != null) {
        replayMap(map, binder, objectVisitor);
      }
      return;
    }
    if (value instanceof Optional<?> optional) {
      optional.ifPresent(v -> visitor.visitMemberValue(name, JsonValue.fromAny(v)));
      return;
    }
    var spec = binder.spec(value.getClass());
    if (spec instanceof ObjectSpec objectSpec) {
      var objectVisitor = visitor.visitMemberObject(name);
      if (objectVisitor != null) {
        objectSpec.replay(value, binder, objectVisitor);
      }
      return;
    }
    if (spec instanceof ValueSpec valueSpec) {
      valueSpec.replayMember(name, value, visitor);
      return;
    }
    visitor.visitMemberValue(name, JsonValue.fromAny(value));
  }

  private static Object replayIterable(Iterable<?> iterable, Binder binder, ArrayVisitor arrayVisitor) {
    arrayVisitor.visitStartArray();
    for(var item: iterable) {
      replayValue(item, binder, arrayVisitor);
    }
    return arrayVisitor.visitEndArray();
  }

  private static Object replayIterator(Iterator<?> iterator, Binder binder, ArrayVisitor arrayVisitor) {
    arrayVisitor.visitStartArray();
    while(iterator.hasNext()) {
      replayValue(iterator.next(), binder, arrayVisitor);
    }
    return arrayVisitor.visitEndArray();
  }

  private static Object replayStream(Stream<?> stream, Binder binder, ArrayVisitor arrayVisitor) {
    arrayVisitor.visitStartArray();
    stream.forEach(item -> replayValue(item, binder, arrayVisitor));
    return arrayVisitor.visitEndArray();
  }

  private static Object replayMap(Map<?,?> map, Binder binder, ObjectVisitor objectVisitor) {
    objectVisitor.visitStartObject();
    map.forEach((key, value) -> {
      var name = key.toString();
      replayMember(name, value, binder, objectVisitor);
    });
    return objectVisitor.visitEndObject();
  }
}
