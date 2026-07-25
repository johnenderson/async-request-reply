package com.async.request.reply.adapter.out.processing;

import com.async.request.reply.spi.Routine;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Indexa todas as {@link Routine} registradas pelo projeto consumidor
 * (síncronas ou assíncronas), por {@link Routine#type()}. Detecta tipos
 * duplicados ou malformados no startup.
 */
public class JobHandlerRegistry {

    /**
     * O {@code type} compõe nomes de chave no storage (índice de single-flight,
     * tópico de eventos) e a rota HTTP, então é validado na origem — não só na
     * borda web.
     */
    private static final Pattern VALID_TYPE = Pattern.compile("[A-Za-z0-9._-]+");

    private final Map<String, Routine> byType;

    public JobHandlerRegistry(List<Routine> routines) {
        routines.forEach(JobHandlerRegistry::validateType);
        this.byType = routines.stream().collect(Collectors.toMap(
                Routine::type,
                Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException(
                            "Mais de uma Routine registrada para o type '" + a.type() + "'");
                }));
    }

    private static void validateType(Routine routine) {
        String type = routine.type();
        if (type == null || !VALID_TYPE.matcher(type).matches()) {
            throw new IllegalStateException("A Routine " + routine.getClass().getName()
                    + " declara o type invalido '" + type
                    + "'; use apenas letras, numeros, ponto, hifen ou underscore.");
        }
    }

    public boolean supports(String type) {
        return type != null && byType.containsKey(type);
    }

    public Optional<Routine> find(String type) {
        return Optional.ofNullable(byType.get(type));
    }
}
