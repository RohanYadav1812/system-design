package com.systemdesign.snakeladder.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.snakeladder.model.Game;
import com.systemdesign.snakeladder.service.GameService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
public class GameServiceImpl implements GameService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxPlayers;
    private final int gameTtlSeconds;

    public GameServiceImpl(RedisTemplate<String, String> redisTemplate,
                           ObjectMapper objectMapper,
                           @Value("${game.max-players:4}") int maxPlayers,
                           @Value("${game.game-ttl-seconds:3600}") int gameTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.maxPlayers = maxPlayers;
        this.gameTtlSeconds = gameTtlSeconds;
    }

    @Override
    public Game createGame(int maxPlayers) {
        String id = UUID.randomUUID().toString();
        Game game = new Game(id, new ArrayList<>(), 0, Game.GameStatus.WAITING, null, System.currentTimeMillis());
        saveGame(game);
        return game;
    }

    @Override
    public Optional<Game> joinGame(String gameId, String playerId, String playerName) {
        Optional<Game> opt = getGame(gameId);
        if (opt.isEmpty()) return Optional.empty();
        Game game = opt.get();
        if (game.players().size() >= maxPlayers) return Optional.empty();
        if (game.status() != Game.GameStatus.WAITING) return Optional.empty();

        List<Game.Player> players = new ArrayList<>(game.players());
        players.add(new Game.Player(playerId, playerName, 1));
        Game updated = new Game(game.id(), players, 0,
                players.size() >= 2 ? Game.GameStatus.PLAYING : Game.GameStatus.WAITING,
                null, game.createdAt());
        saveGame(updated);
        return Optional.of(updated);
    }

    @Override
    public Optional<Game> rollDice(String gameId, String playerId) {
        Optional<Game> opt = getGame(gameId);
        if (opt.isEmpty()) return Optional.empty();
        Game game = opt.get();
        if (game.status() != Game.GameStatus.PLAYING) return Optional.empty();
        if (game.players().isEmpty()) return Optional.empty();

        int turnIndex = game.currentTurn() % game.players().size();
        Game.Player currentPlayer = game.players().get(turnIndex);
        if (!currentPlayer.id().equals(playerId)) return Optional.empty();

        int dice = ThreadLocalRandom.current().nextInt(1, 7);
        int newPos = Math.min(100, currentPlayer.position() + dice);
        newPos = Game.applySnakesAndLadders(newPos);

        List<Game.Player> players = new ArrayList<>();
        for (int i = 0; i < game.players().size(); i++) {
            Game.Player p = game.players().get(i);
            players.add(i == turnIndex ? new Game.Player(p.id(), p.name(), newPos) : p);
        }

        Game.GameStatus status = newPos >= 100 ? Game.GameStatus.FINISHED : Game.GameStatus.PLAYING;
        String winner = newPos >= 100 ? playerId : null;
        int nextTurn = newPos >= 100 ? game.currentTurn() : game.currentTurn() + 1;

        Game updated = new Game(game.id(), players, nextTurn, status, winner, game.createdAt());
        saveGame(updated);
        return Optional.of(updated);
    }

    @Override
    public Optional<Game> getGame(String gameId) {
        String json = redisTemplate.opsForValue().get("game:" + gameId);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(deserialize(json));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private void saveGame(Game game) {
        try {
            redisTemplate.opsForValue().set("game:" + game.id(), serialize(game), gameTtlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private String serialize(Game game) throws JsonProcessingException {
        Map<String, Object> map = new HashMap<>();
        map.put("id", game.id());
        map.put("players", game.players());
        map.put("currentTurn", game.currentTurn());
        map.put("status", game.status().name());
        map.put("winnerId", game.winnerId());
        map.put("createdAt", game.createdAt());
        return objectMapper.writeValueAsString(map);
    }

    private Game deserialize(String json) throws JsonProcessingException {
        Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
        List<Game.Player> players = objectMapper.convertValue(map.get("players"), new TypeReference<>() {});
        return new Game((String) map.get("id"), players, (Integer) map.get("currentTurn"),
                Game.GameStatus.valueOf((String) map.get("status")), (String) map.get("winnerId"),
                ((Number) map.get("createdAt")).longValue());
    }
}
