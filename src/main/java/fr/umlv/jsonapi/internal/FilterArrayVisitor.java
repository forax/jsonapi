package fr.umlv.jsonapi.internal;

import static java.util.Objects.requireNonNull;

import fr.umlv.jsonapi.ArrayVisitor;
import fr.umlv.jsonapi.JsonValue;
import fr.umlv.jsonapi.ObjectVisitor;
import fr.umlv.jsonapi.VisitorMode;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class FilterArrayVisitor implements ArrayVisitor {
  private final ArrayVisitor delegate;
  private final Predicate<? super String> predicate;

  public FilterArrayVisitor(ArrayVisitor delegate, Predicate<? super String> predicate) {
    this.delegate = requireNonNull(delegate);
    this.predicate = requireNonNull(predicate);
  }

  @Override
  public VisitorMode visitStartArray() {
    return delegate.visitStartArray();
  }

  @Override
  public Object visitStream(Stream<Object> stream) {
    return delegate.visitStream(stream);
  }

  @Override
  public ObjectVisitor visitObject() {
    var objectVisitor = this.delegate.visitObject();
    return new FilterObjectVisitor(objectVisitor, predicate);
  }

  @Override
  public ArrayVisitor visitArray() {
    var arrayVisitor = delegate.visitArray();
    return new FilterArrayVisitor(arrayVisitor, predicate);
  }

  @Override
  public Object visitValue(JsonValue value) {
    return delegate.visitValue(value);
  }

  @Override
  public Object visitEndArray() {
    return delegate.visitEndArray();
  }
}