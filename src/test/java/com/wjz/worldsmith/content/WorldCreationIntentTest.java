package com.wjz.worldsmith.content;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WorldCreationIntentTest {
    private static final String OATH="6".repeat(64), JIUZHOU="f".repeat(64);

    @Test void explicitOathfireSelectionRejectsBackgroundJiuzhouPublication() {
        var screen=new Object();
        var intent=new WorldCreationIntent(1,OATH,"Oathfire",true);
        intent.bind(screen);var ticket=intent.beginAttempt(screen);
        assertFalse(intent.acceptsPublication(JIUZHOU));
        assertTrue(intent.acceptsPublication(OATH));
        assertEquals(OATH,intent.packId());
        assertTrue(intent.owns(screen,ticket));
    }

    @Test void authoringMayProposeAnotherPackWithoutAnExplicitPlayerSelection() {
        var intent=new WorldCreationIntent(1,JIUZHOU,"Jiuzhou",false);
        assertTrue(intent.acceptsPublication(OATH));
        assertTrue(intent.acceptsPublication(JIUZHOU));
    }

    @Test void exactScreenAndRequestAreRequiredEvenForTheSameBundle() {
        var screenA=new Object();var screenB=new Object();
        var first=new WorldCreationIntent(1,OATH,"Oathfire",true);first.bind(screenA);
        var second=new WorldCreationIntent(2,OATH,"Oathfire",true);second.bind(screenB);
        var firstTicket=first.beginAttempt(screenA);var secondTicket=second.beginAttempt(screenB);
        assertFalse(first.owns(screenB,firstTicket));
        assertFalse(second.owns(screenB,firstTicket));
        assertFalse(first.owns(screenA,secondTicket));
        assertTrue(second.owns(screenB,secondTicket));
        assertThrows(IllegalStateException.class,()->first.bind(screenB));
    }

    @Test void cancellationInvalidatesExportReloadAndPublicationTickets() {
        var screen=new Object();var intent=new WorldCreationIntent(1,OATH,"Oathfire",true);
        intent.bind(screen);var ticket=intent.beginAttempt(screen);intent.cancel(screen);
        assertTrue(intent.cancelled());
        assertFalse(intent.owns(screen,ticket));
        assertFalse(intent.acceptsPublication(OATH));
        assertThrows(IllegalStateException.class,()->intent.beginAttempt(screen));
    }

    @Test void retryRejectsLateCompletionFromThePreviousAttempt() {
        var screen=new Object();var intent=new WorldCreationIntent(1,OATH,"Oathfire",true);
        intent.bind(screen);var old=intent.beginAttempt(screen);var current=intent.beginAttempt(screen);
        assertFalse(intent.owns(screen,old));assertTrue(intent.owns(screen,current));
    }

    @Test void repeatedScreenInitDoesNotChangeTheRequestOrCurrentAttempt() {
        var screen=new Object();var intent=new WorldCreationIntent(42,OATH,"Oathfire",true);
        intent.bind(screen);var ticket=intent.beginAttempt(screen);intent.bind(screen);
        assertEquals(42,intent.requestId());assertTrue(intent.owns(screen,ticket));
    }

    @Test void unrelatedScreenCannotCancelTheActiveCreation() {
        var screen=new Object();var intent=new WorldCreationIntent(1,OATH,"Oathfire",true);
        intent.bind(screen);var ticket=intent.beginAttempt(screen);
        assertThrows(IllegalStateException.class,()->intent.cancel(new Object()));
        assertTrue(intent.owns(screen,ticket));
    }
}
