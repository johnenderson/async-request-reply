package com.async.request.reply.adapter.out.processing;

import com.async.request.reply.spi.JobHandler;
import com.async.request.reply.spi.Routine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JobHandlerRegistry")
class JobHandlerRegistryTest {

    @ParameterizedTest(name = "type=[{0}]")
    @DisplayName("recusa no startup type que não serve como chave/rota")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "com espaco", "com:dois-pontos", "com/barra", "com*curinga", "acentuação"})
    void constructor_should_reject_invalid_type(String invalidType) {
        assertThatThrownBy(() -> new JobHandlerRegistry(List.of(handler(invalidType))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("type invalido");
    }

    @ParameterizedTest(name = "type={0}")
    @DisplayName("aceita letras, números, ponto, hífen e underscore")
    @ValueSource(strings = {"relatorio", "123", "report.v1_all-items", "a"})
    void constructor_should_accept_valid_type(String validType) {
        assertThatCode(() -> new JobHandlerRegistry(List.of(handler(validType))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("falha quando dois handlers declaram o mesmo type")
    void constructor_should_reject_duplicated_type() {
        assertThatThrownBy(() -> new JobHandlerRegistry(List.of(handler("dup"), handler("dup"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Mais de uma Routine");
    }

    @Test
    @DisplayName("indexa e encontra a routine pelo type")
    void find_should_return_routine_registered_for_the_type() {
        Routine routine = handler("relatorio");
        JobHandlerRegistry registry = new JobHandlerRegistry(List.of(routine));

        assertThat(registry.supports("relatorio")).isTrue();
        assertThat(registry.supports("outro")).isFalse();
        assertThat(registry.supports(null)).isFalse();
        assertThat(registry.find("relatorio")).containsSame(routine);
    }

    private static JobHandler handler(String type) {
        return new JobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void handle() {
            }
        };
    }
}
