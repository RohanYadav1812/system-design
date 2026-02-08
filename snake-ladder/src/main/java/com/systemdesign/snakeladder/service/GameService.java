package com.systemdesign.snakeladder.service;

import com.systemdesign.snakeladder.model.Game;

import java.util.Optional;

public interface GameService {

    Game createGame(int maxPlayers);

    Optional<Game> joinGame(String gameId, String playerId, String playerName);

    Optional<Game> rollDice(String gameId, String playerId);

    Optional<Game> getGame(String gameId);
}
