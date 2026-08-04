CREATE UNIQUE INDEX idx_private_invite_create_command
    ON private_duel_invites(owner_player_id, create_command_id);

CREATE UNIQUE INDEX idx_private_invite_accept_command
    ON private_duel_invites(guest_player_id, accept_command_id);
