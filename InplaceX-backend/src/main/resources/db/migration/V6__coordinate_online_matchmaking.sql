CREATE UNIQUE INDEX idx_matchmaking_command_replay
    ON matchmaking_tickets(player_id, command_id);
