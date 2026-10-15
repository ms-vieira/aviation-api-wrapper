package com.msvieira.aviation.domain;

import com.msvieira.aviation.domain.exception.InvalidIcaoCodeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IcaoCodeTest {

    @Test
    void acceptsFourUppercaseLetters() {
        assertThat(IcaoCode.of("KJFK").value()).isEqualTo("KJFK");
    }

    @Test
    void normalisesLowercaseAndSurroundingWhitespace() {
        assertThat(IcaoCode.of("  sbgr ").value()).isEqualTo("SBGR");
        assertThat(IcaoCode.of("kjfk")).isEqualTo(IcaoCode.of("KJFK"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"K1A", "1234", "KJFKK", "KJF", "KJ-K", "KJFK1", "ÄBCD"})
    void rejectsAnythingThatIsNotFourLetters(String raw) {
        assertThatThrownBy(() -> IcaoCode.of(raw))
                .isInstanceOf(InvalidIcaoCodeException.class)
                .hasMessageContaining("four letters");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsNullAndBlank(String raw) {
        assertThatThrownBy(() -> IcaoCode.of(raw)).isInstanceOf(InvalidIcaoCodeException.class);
    }
}
