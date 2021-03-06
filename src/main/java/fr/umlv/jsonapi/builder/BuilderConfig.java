package fr.umlv.jsonapi.builder;

import static fr.umlv.jsonapi.VisitorMode.PULL;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;
import static java.util.function.UnaryOperator.identity;

import fr.umlv.jsonapi.ArrayVisitor;
import fr.umlv.jsonapi.ObjectVisitor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Factory functions and transformer functions used to construct an {@link ObjectBuilder} or an
 * {@link ArrayBuilder}.
 *
 * <p>Because JSON format is recursive, by example an array may contain objects and an object may
 * contain array, you have to configure the representation used for an object and the representation
 * used for an array both at the same time.
 *
 * <p>The default configuration use {@link LinkedHashMap} for representing JSON objects and {@link
 * ArrayList} for representing JSON arrays (both data structures use the insertion order)
 *
 * <pre>
 *   BuilderConfig config =  BuilderConfig.defaults();
 *   ObjectBuilder builder = config.newObjectBuilder();
 * </pre>
 *
 * You can configure the exact {@link Map} and {@link List} implementations to use, by example use
 * {@link java.util.HashMap} instead of {@link LinkedHashMap} to use less memory (but you loose the
 * insertion order)
 *
 * <pre>
 *   BuilderConfig config = new BuilderConfig(HashMap::new, ArrayList::new);
 *   ArrayBuilder builder = config.newArrayBuilder();
 * </pre>
 *
 * By default, the value returned by {@link ObjectBuilder#toMap()} and  {@link
 * ArrayBuilder#toList()} are the specified implementation wrapped by an unmodifiable wrapper (see
 * {@link Collections#unmodifiableMap(Map)} and * {@link Collections#unmodifiableList(List)}).
 * You may want to avoid this wrapping to get access to the underlying mutable data structure by
 * specifying that the transformation operation executed at the end of the parsing
 * is a no-op
 *
 * <pre>
 *   BuilderConfig config = new BuilderConfig(LinkedHashMap::new, ArrayList::new);
 *     .withTransformOps(UnaryOperator.identity(), UnaryOperator.identity());
 * </pre>
 */
public class BuilderConfig {
  final Supplier<? extends Map<String, Object>> mapSupplier;
  final UnaryOperator<Map<String, Object>> transformMapOp;
  final Supplier<? extends List<Object>> listSupplier;
  final UnaryOperator<List<Object>> transformListOp;

  static final BuilderConfig DEFAULT = new BuilderConfig();

  /**
   * Creates a builder config from a factory of Map, a function to transform the Map
   * to another one at the end of the parsing, a factory of List and the transform function
   * on lists.
   *
   * @param mapSupplier a factory of Map&gt;, Object&gt;
   * @param transformMapOp a function from Map&gt;, Object&gt; to Map&gt;, Object&gt;
   * @param listSupplier a factory of List&gt;Object&gt;
   * @param transformListOp a function from List&gt;Object&gt; to List&gt;Object&gt;
   */
  public BuilderConfig(Supplier<? extends Map<String, Object>> mapSupplier,
      UnaryOperator<Map<String, Object>> transformMapOp,
      Supplier<? extends List<Object>> listSupplier,
      UnaryOperator<List<Object>> transformListOp) {
    this.mapSupplier = requireNonNull(mapSupplier, "mapSupplier");
    this.transformMapOp = requireNonNull(transformMapOp, "transformMapOp");
    this.listSupplier = requireNonNull(listSupplier, "listSupplier");
    this.transformListOp = requireNonNull(transformListOp, "transformListOp");
  }

  /**
   * Creates a builder config from a factory of {@link Map} and a factory of {@link List}.
   *
   * @param mapSupplier a supplier of instances of Map.
   * @param listSupplier a supplier of instances of List.
   */
  public BuilderConfig(Supplier<? extends Map<String, Object>> mapSupplier,
                       Supplier<? extends List<Object>> listSupplier) {
    this(mapSupplier, Collections::unmodifiableMap, listSupplier, Collections::unmodifiableList);
  }

  private BuilderConfig() {
    this(LinkedHashMap::new, ArrayList::new);
  }

  /**
   * Returns a builder configuration that use a factory of {@link LinkedHashMap} and
   * a factory of {@link ArrayList} to be used respectively by {@link ObjectBuilder}s
   * and {@link ArrayBuilder}s
   * @return the default builder configuration
   */
  public static BuilderConfig defaults() {
    return DEFAULT;
  }

  /**
   * Replace the current transformations operations applied on {@link Map}s and {@link List}s
   * once the object/array is fully initialized by new ones.
   *
   * @param transformMapOp the new transformation to apply for the {@link Map}s.
   * @param transformListOp the new transformation to apply for the {@link List}s.
   * @return a new builder configuration.
   *
   * @see #withTransformMapOp(UnaryOperator)
   * @see #withTransformListOp(UnaryOperator)
   */
  public BuilderConfig withTransformOps(
      UnaryOperator<Map<String, Object>> transformMapOp,
      UnaryOperator<List<Object>> transformListOp) {
    return new BuilderConfig(mapSupplier, transformMapOp, listSupplier, transformListOp);
  }

  /**
   * Replace the current transformation operation applied on {@link Map}s
   * once the object is fully initialized by a new one.
   *
   * @param transformMapOp the new transformation to apply to the {@link Map}s.
   * @return a new builder configuration.
   *
   * @see #withTransformOps(UnaryOperator, UnaryOperator)
   */
  public BuilderConfig withTransformMapOp(UnaryOperator<Map<String, Object>> transformMapOp) {
    return new BuilderConfig(mapSupplier, transformMapOp, listSupplier, transformListOp);
  }

  /**
   * Replace the current transformation operation applied on {@link List}s
   * once the array is fully initialized by a new one.
   *
   * @param transformListOp the new transformation to apply to the {@link List}s.
   * @return a new builder configuration.
   *
   * @see #withTransformOps(UnaryOperator, UnaryOperator)
   */
  public BuilderConfig withTransformListOp(UnaryOperator<List<Object>> transformListOp) {
    return new BuilderConfig(mapSupplier, transformMapOp, listSupplier, transformListOp);
  }

  /**
   * Returns a new object builder initialized with the current configuration.
   * @return a new object builder initialized with the current configuration.
   */
  public ObjectBuilder newObjectBuilder() {
    return new ObjectBuilder(this, (ObjectVisitor) null);
  }

  /**
   * Return a new object builder initialized with the current configuration
   * and with a {code delegate} that will be used to react to the visit methods
   * called on the object builder.
   *
   * The values returned by a call to {@link ObjectVisitor#visitMemberObject(String)},
   * {@link ObjectVisitor#visitMemberArray(String)} and
   * {@link ObjectVisitor#visitMemberValue(String, fr.umlv.jsonapi.JsonValue)}
   * are stored in the {@link Map} stored in the created {@link ObjectBuilder}.
   *
   * The {code delegate} has to be a visitor in {@link fr.umlv.jsonapi.VisitorMode#PULL} mode.
   *
   * @param delegate an object builder that will be used to react to the visit methods or null.
   * @return a new object builder that delegate its operation to an object visitor.
   */
  public ObjectBuilder newObjectBuilder(ObjectVisitor delegate) {
    if (delegate != null && delegate.visitStartObject() != PULL) {
      throw new IllegalArgumentException("only pull mode visitors are allowed");
    }
    return new ObjectBuilder(this, delegate);
  }

  /**
   * Creates an unmodifiable object builder that wrap an existing java.util.Map
   * @param map a java.util.Map
   */
  public ObjectBuilder wrap(Map<String, ?> map) {
    return new ObjectBuilder(this, unmodifiableMap(map));
  }

  /**
   * Returns a new array builder initialized with the current configuration.
   * @return a new array builder initialized with the current configuration.
   */
  public ArrayBuilder newArrayBuilder() {
    return new ArrayBuilder(this, (ArrayVisitor) null);
  }

  /**
   * Return a new array builder initialized with the current configuration
   * and with a {code delegate} that will be used to react to the visit methods
   * called on the array builder.
   *
   * The values returned by a call to {@link ArrayVisitor#visitObject()},
   * {@link ArrayVisitor#visitArray()} and
   * {@link ArrayVisitor#visitValue(fr.umlv.jsonapi.JsonValue)} are stored in the {@link Map}
   * stored in the created {@link ObjectBuilder}.
   *
   * The {code delegate} has to be a visitor in {@link fr.umlv.jsonapi.VisitorMode#PULL} mode.
   *
   * @param delegate an object builder that will be used to react to the visit methods or null.
   * @return a new object builder that delegate its operation to an object visitor.
   */
  public ArrayBuilder newArrayBuilder(ArrayVisitor delegate) {
    if (delegate != null && delegate.visitStartArray() != PULL) {
      throw new IllegalArgumentException("only pull mode visitors are allowed");
    }
    return new ArrayBuilder(this, delegate);
  }

  /**
   * Creates an unmodifiable array builder that wrap an existing java.util.List
   * @param list a java.util.List
   */
  public ArrayBuilder wrap(List<?> list) {
    return new ArrayBuilder(this, unmodifiableList(list));
  }
}
