/*
 * Copyright © Wynntils 2022.
 * This file is released under AGPLv3. See LICENSE for full license details.
 */
package com.wynntils.handlers.scoreboard;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.managers.Handler;
import com.wynntils.handlers.scoreboard.event.ScoreboardSegmentAdditionEvent;
import com.wynntils.mc.event.ScoreboardSetScoreEvent;
import com.wynntils.mc.utils.ComponentUtils;
import com.wynntils.mc.utils.McUtils;
import com.wynntils.utils.Pair;
import com.wynntils.wynn.event.WorldStateEvent;
import com.wynntils.wynn.model.WorldStateManager;
import com.wynntils.wynn.model.scoreboard.ScoreboardModel;
import com.wynntils.wynn.utils.WynnUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class ScoreboardHandler extends Handler {
    // TimeUnit.MILLISECONDS
    // 250 -> 4 times a second
    private static final int CHANGE_PROCESS_RATE = 250;

    private List<ScoreboardLine> reconstructedScoreboard = new ArrayList<>();

    private List<Segment> segments = new ArrayList<>();

    private final LinkedList<ScoreboardLineChange> queuedChanges = new LinkedList<>();

    private final List<Pair<ScoreboardListener, Set<ScoreboardModel.SegmentType>>> scoreboardListeners =
            new ArrayList<>();

    private ScheduledExecutorService executor = null;

    private boolean firstExecution = false;

    private void periodicTask() {
        if (!WynnUtils.onWorld() || McUtils.player() == null) return;

        if (queuedChanges.isEmpty()) {
            handleScoreboardReconstruction();
            return;
        }

        List<ScoreboardLine> scoreboardCopy = new ArrayList<>(reconstructedScoreboard);
        LinkedList<ScoreboardLineChange> queueCopy = new LinkedList<>(queuedChanges);
        queuedChanges.clear();

        Map<Integer, ScoreboardLine> scoreboardLineMap = new TreeMap<>();

        for (ScoreboardLine scoreboardLine : scoreboardCopy) {
            scoreboardLineMap.put(scoreboardLine.index(), scoreboardLine);
        }

        Set<String> changedLines = new HashSet<>();
        while (!queueCopy.isEmpty()) {
            ScoreboardLineChange processed = queueCopy.pop();

            if (processed.method() == ServerScoreboard.Method.REMOVE) {
                for (ScoreboardLine lineToRemove : scoreboardLineMap.values().stream()
                        .filter(scoreboardLine -> Objects.equals(scoreboardLine.line(), processed.lineText()))
                        .toList()) {
                    scoreboardLineMap.remove(lineToRemove.index());
                }
            } else {
                ScoreboardLine line = new ScoreboardLine(processed.lineText(), processed.lineIndex());
                scoreboardLineMap.put(line.index(), line);
                changedLines.add(processed.lineText());
            }
        }

        scoreboardCopy.clear();

        scoreboardCopy = scoreboardLineMap.values().stream()
                .sorted(Comparator.comparing(ScoreboardLine::index).reversed())
                .collect(Collectors.toList());

        List<Segment> parsedSegments = calculateSegments(scoreboardCopy);

        for (String changedString : changedLines) {
            int changedLine =
                    scoreboardCopy.stream().map(ScoreboardLine::line).toList().indexOf(changedString);

            if (changedLine == -1) {
                continue;
            }

            Optional<Segment> foundSegment = parsedSegments.stream()
                    .filter(segment -> segment.getStartIndex() <= changedLine
                            && segment.getEndIndex() >= changedLine
                            && !segment.isChanged())
                    .findFirst();

            if (foundSegment.isEmpty()) {
                continue;
            }

            // Prevent bugs where content was not changed, but rather replaced to prepare room for other segment updates
            //  (Objective -> Daily Objective update)
            Optional<Segment> oldMatchingSegment = segments.stream()
                    .filter(segment -> segment.getType() == foundSegment.get().getType())
                    .findFirst();

            if (oldMatchingSegment.isEmpty()) {
                foundSegment.get().setChanged(true);
                continue;
            }

            if (!oldMatchingSegment.get().getContent().equals(foundSegment.get().getContent())
                    || !Objects.equals(
                            oldMatchingSegment.get().getHeader(),
                            foundSegment.get().getHeader())) {
                foundSegment.get().setChanged(true);
            }
        }

        List<Segment> removedSegments = segments.stream()
                .filter(segment -> parsedSegments.stream().noneMatch(parsed -> parsed.getType() == segment.getType()))
                .toList();

        reconstructedScoreboard = scoreboardCopy;
        segments = parsedSegments;

        for (Segment segment : removedSegments) {
            for (Pair<ScoreboardListener, Set<ScoreboardModel.SegmentType>> scoreboardListener : scoreboardListeners) {
                if (scoreboardListener.b().contains(segment.getType())) {
                    scoreboardListener.a().onSegmentRemove(segment, segment.getType());
                }
            }
        }

        List<Segment> changedSegments;

        if (firstExecution) {
            firstExecution = false;
            changedSegments = parsedSegments.stream().toList();
        } else {
            changedSegments = parsedSegments.stream().filter(Segment::isChanged).toList();
        }

        for (Segment segment : changedSegments) {
            for (Pair<ScoreboardListener, Set<ScoreboardModel.SegmentType>> scoreboardListener : scoreboardListeners) {
                if (scoreboardListener.b().contains(segment.getType())) {
                    scoreboardListener.a().onSegmentChange(segment, segment.getType());
                }
            }
        }

        handleScoreboardReconstruction();
    }

    private void handleScoreboardReconstruction() {
        McUtils.mc().doRunTask(() -> {
            Scoreboard scoreboard = McUtils.player().getScoreboard();

            List<String> skipped = new ArrayList<>();

            for (Segment parsedSegment : segments) {
                boolean cancelled = WynntilsMod.postEvent(new ScoreboardSegmentAdditionEvent(parsedSegment));

                if (cancelled) {
                    skipped.addAll(parsedSegment.getScoreboardLines());
                }
            }

            final String objectiveName = "wynntilsSB" + McUtils.player().getScoreboardName();

            Objective objective = scoreboard.getObjective(objectiveName);

            if (objective == null) {
                objective = scoreboard.addObjective(
                        objectiveName,
                        ObjectiveCriteria.DUMMY,
                        Component.literal(" play.wynncraft.com")
                                .withStyle(ChatFormatting.GOLD)
                                .withStyle(ChatFormatting.BOLD),
                        ObjectiveCriteria.RenderType.INTEGER);
            }

            scoreboard.setDisplayObjective(1, objective);

            // Set player team display objective
            // This fixes scoreboard gui flickering
            PlayerTeam playerTeam = scoreboard.getPlayersTeam(McUtils.player().getScoreboardName());
            if (playerTeam != null) {
                if (playerTeam.getColor().getId() >= 0) {
                    int id = playerTeam.getColor().getId() + 3;
                    scoreboard.setDisplayObjective(id, objective);
                }
            }

            for (Map<Objective, Score> scoreMap : scoreboard.playerScores.values()) {
                scoreMap.remove(objective);
            }

            // Filter and skip leading empty lines
            List<ScoreboardLine> toBeAdded = reconstructedScoreboard.stream()
                    .filter(scoreboardLine -> !skipped.contains(scoreboardLine.line()))
                    .dropWhile(scoreboardLine -> scoreboardLine.line().matches("À+"))
                    .toList();

            boolean allEmpty = true;

            // Skip trailing empty lines
            for (int i = toBeAdded.size() - 1; i >= 0; i--) {
                if (allEmpty && toBeAdded.get(i).line().matches("À+")) {
                    continue;
                }

                allEmpty = false;
                Score score = scoreboard.getOrCreatePlayerScore(toBeAdded.get(i).line(), objective);
                score.setScore(toBeAdded.get(i).index());
            }
        });
    }

    private List<Segment> calculateSegments(List<ScoreboardLine> scoreboardCopy) {
        List<Segment> segments = new ArrayList<>();

        Segment currentSegment = null;

        for (int i = 0; i < scoreboardCopy.size(); i++) {
            String strippedLine =
                    ComponentUtils.stripFormatting(scoreboardCopy.get(i).line());

            if (strippedLine == null) {
                continue;
            }

            if (strippedLine.matches("À+")) {
                if (currentSegment != null) {
                    currentSegment.setContent(scoreboardCopy.stream()
                            .map(ScoreboardLine::line)
                            .collect(Collectors.toList())
                            .subList(currentSegment.getStartIndex() + 1, i));
                    currentSegment.setEndIndex(i - 1);
                    currentSegment.setEnd(strippedLine);
                    segments.add(currentSegment);
                    currentSegment = null;
                }

                continue;
            }

            for (ScoreboardModel.SegmentType value : ScoreboardModel.SegmentType.values()) {
                if (!value.getHeaderPattern().matcher(strippedLine).matches()) continue;

                if (currentSegment != null) {
                    if (currentSegment.getType() != value) {
                        WynntilsMod.error(
                                "ScoreboardModel: currentSegment was not null and SegmentType was mismatched. We might have skipped a scoreboard category.");
                    }
                    continue;
                }

                currentSegment = new Segment(value, scoreboardCopy.get(i).line(), i);
                break;
            }
        }

        if (currentSegment != null) {
            currentSegment.setContent(scoreboardCopy.stream()
                    .map(ScoreboardLine::line)
                    .collect(Collectors.toList())
                    .subList(currentSegment.getStartIndex(), scoreboardCopy.size()));
            currentSegment.setEndIndex(scoreboardCopy.size() - 1);
            segments.add(currentSegment);
        }

        return segments;
    }

    public void init() {
        startThread();
    }

    public void disable() {
        resetState();
        scoreboardListeners.clear();
    }

    public void registerListener(ScoreboardListener listener, ScoreboardModel.SegmentType segmentType) {
        scoreboardListeners.add(new Pair<>(listener, Set.of(segmentType)));
    }

    public void registerListener(ScoreboardListener listener, Set<ScoreboardModel.SegmentType> segmentTypes) {
        scoreboardListeners.add(new Pair<>(listener, segmentTypes));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onSetScore(ScoreboardSetScoreEvent event) {
        queuedChanges.add(new ScoreboardLineChange(event.getOwner(), event.getMethod(), event.getScore()));
    }

    @SubscribeEvent
    public void onWorldStateChange(WorldStateEvent event) {
        if (event.getNewState() == WorldStateManager.State.WORLD) {
            startThread();
            return;
        }

        resetState();
    }

    private void startThread() {
        firstExecution = true;
        executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(this::periodicTask, 0, CHANGE_PROCESS_RATE, TimeUnit.MILLISECONDS);
    }

    private void resetState() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        queuedChanges.clear();
        reconstructedScoreboard.clear();
        segments.clear();

        for (Pair<ScoreboardListener, Set<ScoreboardModel.SegmentType>> scoreboardListener : scoreboardListeners) {
            scoreboardListener.a().reset();
        }
    }
}