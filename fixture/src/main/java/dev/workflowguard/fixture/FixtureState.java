package dev.workflowguard.fixture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class FixtureState {
    private final Map<String, Invitation> invitations = new LinkedHashMap<>();
    private final Set<String> members = new LinkedHashSet<>();
    private int nextInvitationId = 1;

    synchronized InvitationSnapshot createInvitation(String recipient) {
        String id = "inv-" + nextInvitationId++;
        Invitation invitation = new Invitation(id, recipient);
        invitations.put(id, invitation);
        return invitation.snapshot();
    }

    synchronized Optional<AcceptResult> acceptInvitation(String invitationId, FixtureMode mode) {
        Invitation invitation = invitations.get(invitationId);
        if (invitation == null) {
            return Optional.empty();
        }

        if (!invitation.accepted && !invitation.revoked) {
            invitation.accepted = true;
            members.add(invitation.recipient);
            return Optional.of(new AcceptResult(invitation.snapshot(), true, false));
        }

        boolean replayChangedState = mode == FixtureMode.VULNERABLE && invitation.revoked;
        if (replayChangedState) {
            members.add(invitation.recipient);
        }
        return Optional.of(new AcceptResult(invitation.snapshot(), replayChangedState, true));
    }

    synchronized boolean revokeMember(String memberId) {
        boolean existed = members.remove(memberId);
        invitations.values().stream()
                .filter(invitation -> invitation.recipient.equals(memberId))
                .forEach(invitation -> invitation.revoked = true);
        return existed;
    }

    synchronized List<String> members() {
        return new ArrayList<>(members);
    }

    synchronized void reset() {
        invitations.clear();
        members.clear();
        nextInvitationId = 1;
    }

    record InvitationSnapshot(
            String id,
            String recipient,
            boolean accepted,
            boolean revoked
    ) {
    }

    record AcceptResult(
            InvitationSnapshot invitation,
            boolean stateChanged,
            boolean conflict
    ) {
    }

    private static final class Invitation {
        private final String id;
        private final String recipient;
        private boolean accepted;
        private boolean revoked;

        private Invitation(String id, String recipient) {
            this.id = id;
            this.recipient = recipient;
        }

        private InvitationSnapshot snapshot() {
            return new InvitationSnapshot(id, recipient, accepted, revoked);
        }
    }
}
