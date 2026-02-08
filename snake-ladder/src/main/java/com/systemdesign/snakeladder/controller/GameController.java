package com.systemdesign.snakeladder.controller;

import com.systemdesign.snakeladder.model.Game;
import com.systemdesign.snakeladder.service.GameService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> create(@RequestParam(defaultValue = "4") int maxPlayers) {
        Game game = gameService.createGame(maxPlayers);
        return ResponseEntity.ok(Map.of("gameId", game.id(), "status", game.status().name()));
    }

    @PostMapping("/{gameId}/join")
    public ResponseEntity<?> join(@PathVariable String gameId,
                                   @RequestParam String playerId,
                                   @RequestParam String playerName) {
        return gameService.joinGame(gameId, playerId, playerName)
                .map(g -> ResponseEntity.ok(Map.of("status", "joined", "players", g.players().size())))
                .orElse(ResponseEntity.status(409).body(Map.of("error", "Cannot join")));
    }

    @PostMapping("/{gameId}/roll")
    public ResponseEntity<?> roll(@PathVariable String gameId, @RequestParam String playerId) {
        return gameService.rollDice(gameId, playerId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(404).build());
    }

    @GetMapping("/{gameId}")
    public ResponseEntity<Game> get(@PathVariable String gameId) {
        return gameService.getGame(gameId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
