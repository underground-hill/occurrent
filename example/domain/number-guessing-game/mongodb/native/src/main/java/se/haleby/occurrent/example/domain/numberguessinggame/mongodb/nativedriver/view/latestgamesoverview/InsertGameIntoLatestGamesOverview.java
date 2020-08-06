package se.haleby.occurrent.example.domain.numberguessinggame.mongodb.nativedriver.view.latestgamesoverview;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import io.cloudevents.CloudEvent;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jetbrains.annotations.NotNull;
import se.haleby.occurrent.changestreamer.mongodb.nativedriver.blocking.BlockingChangeStreamerWithPositionPersistenceForMongoDB;
import se.haleby.occurrent.example.domain.numberguessinggame.model.domainevents.*;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.mongodb.client.model.Filters.eq;
import static java.time.ZoneOffset.UTC;
import static java.time.temporal.ChronoUnit.MILLIS;

public class InsertGameIntoLatestGamesOverview {

    public static void insertGameIntoLatestGamesOverview(BlockingChangeStreamerWithPositionPersistenceForMongoDB changeStreamer,
                                                         MongoCollection<Document> latestGamesOverviewCollection,
                                                         Function<CloudEvent, GameEvent> deserialize) {
        changeStreamer.stream(InsertGameIntoLatestGamesOverview.class.getSimpleName(), insertGame(latestGamesOverviewCollection, deserialize));
    }

    @NotNull
    private static Consumer<CloudEvent> insertGame(MongoCollection<Document> latestGamesOverviewCollection, Function<CloudEvent, GameEvent> deserialize) {
        return cloudEvent -> {
            GameEvent gameEvent = deserialize.apply(cloudEvent);

            if (gameEvent instanceof NumberGuessingGameWasStarted) {
                NumberGuessingGameWasStarted e = (NumberGuessingGameWasStarted) gameEvent;
                Document game = new Document();
                game.put("_id", gameEvent.gameId().toString());
                game.put("startedAt", toDate(gameEvent.timestamp()));
                game.put("numberOfGuesses", 0);
                game.put("maxNumberOfGuesses", e.maxNumberOfGuesses());
                latestGamesOverviewCollection.insertOne(game);
            } else {
                Bson findGame = eq("_id", gameEvent.gameId().toString());
                Document game = latestGamesOverviewCollection.find(findGame).first();
                if (game == null) { // This game is not one of the latest
                    return;
                }

                if (gameEvent instanceof PlayerGuessedANumberThatWasTooSmall || gameEvent instanceof PlayerGuessedANumberThatWasTooBig) {
                    game.put("numberOfGuesses", (int) game.get("numberOfGuesses") + 1);
                } else if (gameEvent instanceof PlayerGuessedTheRightNumber) {
                    game.put("numberOfGuesses", (int) game.get("numberOfGuesses") + 1);
                    game.put("playerGuessedTheRightNumber", true);
                } else if (gameEvent instanceof GuessingAttemptsExhausted) {
                    game.put("playerGuessedTheRightNumber", false);
                } else if (gameEvent instanceof NumberGuessingGameEnded) {
                    game.put("endedAt", toDate(gameEvent.timestamp()));
                }

                latestGamesOverviewCollection.replaceOne(findGame, game, new ReplaceOptions().upsert(true));
            }
        };
    }

    private static Date toDate(LocalDateTime timestamp) {
        return Date.from(timestamp.truncatedTo(MILLIS).toInstant(UTC));
    }
}