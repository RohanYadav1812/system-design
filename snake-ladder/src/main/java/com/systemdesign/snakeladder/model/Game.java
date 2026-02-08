package com.systemdesign.snakeladder.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record Game(
        String id,
        List<Player> players,
        int currentTurn,
        GameStatus status,
        String winnerId,
        long createdAt
) {
    public enum GameStatus { WAITING, PLAYING, FINISHED }

    public record Player(String id, String name, int position) {}

    private static final Map<Integer, Integer> SNAKES = Map.of(
            16, 6, 46, 25, 49, 11, 62, 19, 64, 60, 74, 53, 89, 68, 92, 88, 95, 75, 99, 80
    );
    private static final Map<Integer, Integer> LADDERS = Map.of(
            2, 38, 7, 14, 8, 31, 15, 26, 21, 42, 28, 84, 36, 44, 51, 67, 71, 91, 78, 98
    );

    public static int applySnakesAndLadders(int position) {
        return LADDERS.getOrDefault(position, SNAKES.getOrDefault(position, position));
    }
}
