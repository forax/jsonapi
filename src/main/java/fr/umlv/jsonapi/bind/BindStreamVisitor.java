package fr.umlv.jsonapi.bind;

import fr.umlv.jsonapi.ArrayVisitor;
import fr.umlv.jsonapi.JsonValue;
import fr.umlv.jsonapi.ObjectVisitor;
import fr.umlv.jsonapi.VisitorMode;
import fr.umlv.jsonapi.bind.Specs.StreamSpec;
import java.util.function.Consumer;
import java.util.stream.Stream;

final class BindStreamVisitor implements ArrayVisitor {
  private final StreamSpec spec;

  private final Consumer<Object> postOp;

  BindStreamVisitor(StreamSpec spec, Consumer<Object> postOp) {
    this.spec = spec;
    this.postOp = postOp;
  }

  BindStreamVisitor(StreamSpec spec) {
    this(spec, __ -> { /* empty */ });
  }

  @Override
  public VisitorMode visitStartArray() {
    return VisitorMode.PULL_INSIDE;
  }

  @Override
  public Object visitStream(Stream<Object> stream) {
    var result = spec.aggregator().apply(stream);
    postOp.accept(result);
    return result;
  }

  @Override
  public ObjectVisitor visitObject() {
    return spec.newObjectFrom();
  }

  @Override
  public ArrayVisitor visitArray() {
    return spec.newArrayFrom();
  }

  @Override
  public Object visitValue(JsonValue value) {
    return Specs.convert(spec, value).asObject();
  }

  @Override
  public Void visitEndArray() {
    return null;
  }
}
