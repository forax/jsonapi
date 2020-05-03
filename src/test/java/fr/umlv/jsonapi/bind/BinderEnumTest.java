package fr.umlv.jsonapi.bind;

import static fr.umlv.jsonapi.bind.Binder.IN_ARRAY;
import static java.lang.invoke.MethodHandles.lookup;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

public class BinderEnumTest {
  enum Policy { all, none }

  @Test
  public void readEnum() {
    var binder = new Binder(lookup());
    var json = """
        [ { "policy": "none" }, { "policy": "all" } ]
        """;
    record Contract(Policy policy) { }
    List<Contract> contracts = binder.read(json, Contract.class, IN_ARRAY);
    assertEquals(List.of(new Contract(Policy.none), new Contract(Policy.all)), contracts);
  }

  @Test
  public void writeEnum() {
    var binder = new Binder(lookup());
    record Contract(Policy policy) { }
    String text = binder.write(new Contract(Policy.all));
    assertEquals("""
        { "policy": "all" }\
        """, text);
  }
}