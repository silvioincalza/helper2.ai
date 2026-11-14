package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.api.common.model.SortOrderDto;
import ai.helper2.api.common.model.TriStateDto;
import org.junit.jupiter.api.Test;

class ContractEnumConvertersTest {

  private final ContractEnumConverters.FromValue converters =
      new ContractEnumConverters.FromValue();

  @Test
  void aSharedEnumBindsByItsValue() {
    assertThat(converters.getConverter(TriStateDto.class).convert("unknown"))
        .isEqualTo(TriStateDto.UNKNOWN);
    assertThat(converters.getConverter(TriStateDto.class).convert(" true "))
        .isEqualTo(TriStateDto.TRUE);
  }

  @Test
  void anEmptyValueIsNoValue() {
    assertThat(converters.getConverter(SortOrderDto.class).convert("")).isNull();
  }

  @Test
  void anEnumOutsideTheContractBindsByName() {
    assertThat(ContractEnumConverters.isContractType(Thread.State.class)).isFalse();
    assertThat(converters.getConverter(Thread.State.class).convert("NEW"))
        .isEqualTo(Thread.State.NEW);
  }
}
