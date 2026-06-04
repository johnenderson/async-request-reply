package com.async.request.reply.adapter.out.processing;

import com.async.request.reply.spi.Routine;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Indexa todas as {@link Routine} registradas pelo projeto consumidor
 * (síncronas ou assíncronas), por {@link Routine#type()}. Detecta tipos
 * duplicados no startup.
 */
public class JobHandlerRegistry {

    private final Map<String, Routine> byType;

    public JobHandlerRegistry(List<Routine> routines) {
        this.byType = routines.stream().collect(Collectors.toMap(
                Routine::type,
                Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException(
                            "Mais de uma Routine registrada para o type '" + a.type() + "'");
                }));
    }

    public boolean supports(String type) {
        return type != null && byType.containsKey(type);
    }

    public Optional<Routine> find(String type) {
        return Optional.ofNullable(byType.get(type));
    }
}
