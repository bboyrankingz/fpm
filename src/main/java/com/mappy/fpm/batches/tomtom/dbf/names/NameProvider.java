package com.mappy.fpm.batches.tomtom.dbf.names;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.mappy.fpm.batches.tomtom.TomtomFolder;
import lombok.extern.slf4j.Slf4j;
import org.jamel.dbf.DbfReader;
import org.jamel.dbf.structure.DbfRow;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static java.util.Collections.emptyMap;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Slf4j
@Singleton
public class NameProvider {

    private final Map<Long, List<AlternativeName>> alternateNames = newHashMap();
    private final Map<Long, List<AlternativeName>> alternateCityNames = newHashMap();
    private final TomtomFolder folder;

    @Inject
    public NameProvider(TomtomFolder folder) {
        this.folder = folder;
    }

    public void loadAlternateNames(String filename) {
        alternateNames.putAll(readFile(filename, "gc.dbf".equals(filename) ? "FULLNAME" : "NAME", "gc.dbf".equals(filename)));
    }

    public void loadAlternateCityNames(String filename) {
        alternateCityNames.putAll(readFile(filename, "NAME", false));
    }

    public Map<String, String> getAlternateNames(Long tomtomId) {
        return getAlternateNames(tomtomId, alternateNames);
    }

    public Map<String, String> getAlternateCityNames(Long tomtomId) {
        return getAlternateNames(tomtomId, alternateCityNames);
    }

    public Map<String, String> getAlternateRoadSideNames(Long tomtomId, Integer sol) {
        return getAlternateRoadNames(tomtomId, sol);
    }

    private Map<String, String> getAlternateNames(Long tomtomId, Map<Long, List<AlternativeName>> alternateNames) {
        return ofNullable(alternateNames.get(tomtomId))
                .orElse(ImmutableList.of())
                .stream()
                .collect(Collectors.toMap(this::getKeyAlternativeName, AlternativeName::getName, mergeIntoMap()));
    }

    private String getKeyAlternativeName(AlternativeName alternativeName) {
        try {
            String keyPrefix = "ON".equals(alternativeName.getType()) ? "name:" : "alt_name:";
            return keyPrefix + Language.valueOf(alternativeName.getLanguage()).getValue();
        } catch (IllegalArgumentException e) {
            log.info("Language not found : {}", e.getMessage());
            return "alt_name";
        }
    }

    private BinaryOperator<String> mergeIntoMap() {
        return (key1, key2) -> key2;
    }

    private Map<String, String> getAlternateRoadNames(Long tomtomId, Integer sol) {
        if (sol == 0) {
            return emptyMap();
        }
        Optional<List<AlternativeName>> alternativeNames = ofNullable(alternateNames.get(tomtomId));
        Map<String, String> tags = newHashMap();
        alternativeNames.ifPresent(alternativeNames1 -> alternativeNames1.forEach(alternativeName -> {
            Optional<String> side = getSideOfLine(alternativeName.getSideOfLine());
            side.ifPresent(s -> {
                tags.put("name:" + side.get(), alternativeName.getName());
                tagRoadNameWithLanguageOrNothing(tags, alternativeName, side.get());
            });
        }));
        return tags;
    }

    private void tagRoadNameWithLanguageOrNothing(Map<String, String> tags, AlternativeName alternativeName, String side) {
        try {
            tags.put("name:" + side + ":" + Language.valueOf(alternativeName.getLanguage()).getValue(), alternativeName.getName());
        } catch (IllegalArgumentException e) {
            log.info("Language not found : {}", e.getMessage());
        }
    }

    private Optional<String> getSideOfLine(Long side) {
        if (side == 1) {
            return of("left");
        } else if (side == 2) {
            return of("right");
        }
        return empty();
    }

    private Map<Long, List<AlternativeName>> readFile(String filename, String alternativeParamName, boolean hasSideName) {
        Map<Long, List<AlternativeName>> alternates = newHashMap();

        File file = new File(folder.getFile(filename));
        if (file.exists()) {
            log.info("Reading {}", file);
            try (DbfReader reader = new DbfReader(file)) {
                DbfRow row;
                Stopwatch stopwatch = Stopwatch.createStarted();
                int counter = 0;

                while ((row = reader.nextRow()) != null) {
                    AlternativeName altName = AlternativeName.fromDbf(row, alternativeParamName, hasSideName);
                    List<AlternativeName> altNames = alternates.containsKey(altName.getId()) ? alternates.get(altName.getId()) : newArrayList();
                    altNames.add(altName);
                    alternates.put(altName.getId(), altNames);
                    counter++;
                }
                long time = stopwatch.elapsed(MILLISECONDS);
                stopwatch.stop();
                log.info("Added {} object(s){}", counter, counter > 0 ? " in " + time + " ms at rate " + String.format("%.2f", counter * 1.0 / time) + " obj/ms" : "");
            }

        } else {
            log.info("File not found : {}", file.getAbsolutePath());
        }

        return alternates;
    }
}
