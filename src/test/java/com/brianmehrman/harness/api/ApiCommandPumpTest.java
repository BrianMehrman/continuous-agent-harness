package com.brianmehrman.harness.api;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import com.brianmehrman.harness.runs.CommandDispatcher;
import org.junit.jupiter.api.Test;

class ApiCommandPumpTest {
    @Test
    void claimedDeliveryFailureAllowsAnotherReadyCommand() {
        var dispatcher = mock(CommandDispatcher.class);
        doThrow(new CommandDispatcher.ClaimedDeliveryException(new IllegalStateException("gateway unavailable")))
                .doReturn(true, false).when(dispatcher).dispatchOnce();

        new ApiCommandPump(dispatcher, true).drain();

        verify(dispatcher, times(3)).dispatchOnce();
    }

    @Test
    void failedDeliveryEndsTheTick() {
        var dispatcher = mock(CommandDispatcher.class);
        doThrow(new IllegalStateException("one failed command"))
                .when(dispatcher).dispatchOnce();

        new ApiCommandPump(dispatcher, true).drain();

        verify(dispatcher, times(1)).dispatchOnce();
    }
}
