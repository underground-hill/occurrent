package se.haleby.occurrent.example.domain.numberguessinggame.mongodb.nativedriver.view.latestgamesoverview;

import java.util.stream.Stream;

@FunctionalInterface
public interface LatestGamesOverview {
    Stream<GameOverview> findOverviewOfLatestGames();
}