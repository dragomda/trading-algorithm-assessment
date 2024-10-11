package codingblackfemales.gettingstarted;

import codingblackfemales.algo.AlgoLogic;
import codingblackfemales.sotw.ChildOrder;
import messages.marketdata.*;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static junit.framework.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test plugs together all the infrastructure, including the order book (which you can trade against)
 * and the market data feed.
 *
 * If your algo adds orders to the book, they will reflect in your market data coming back from the order book.
 *
 * If you cross the srpead (i.e. you BUY an order with a price which is == or > askPrice()) you will match, and receive
 * a fill back into your order from the order book (visible from the algo in the childOrders of the state object.
 *
 * If you cancel the order your child order will show the order status as cancelled in the childOrders of the state object.
 *
 */
public class StretchAlgoBackTest2 extends AbstractAlgoBackTest {

    @Override
    public AlgoLogic createAlgoLogic() {
        return new StretchAlgoLogic();
    }

    @Test
    public void testPlacingBuyingConditions() throws Exception {
        // send 6 market ticks to calculate 6 moving averages
//        send(createTick());
        send(createTickBuyLow1());
        send(createTickBuyLow1());
        send(createTickBuyLow2());
        send(createTickBuyLow2());
        send(createTickBuyLow3());
        send(createTickBuyLow3());
        // one last tick
        send(createTickBuyLow3());

        // Assert to check we have created 3 child orders under good buy conditions
        assertEquals(3, container.getState().getChildOrders().size());
        assertEquals(3, container.getState().getActiveChildOrders().size());
        assertTrue(container.getState().getActiveChildOrders().stream().allMatch(childOrder -> childOrder.getSide().toString().equals("BUY")));

        // send a tick that would fill out orders
        send(createTickFillBUYOrders());

        //then: get the state
        var state = container.getState();

        //Check things like filled quantity, cancelled order count etc....
        long filledQuantity = state.getChildOrders().stream().map(ChildOrder::getFilledQuantity).reduce(Long::sum).get();
        //and: check that our algo state was updated to reflect our fills when the market data
        assertEquals(300, filledQuantity);
    }

    @Test
    public void testPlacingSellingConditions() throws Exception {
        // send 6 market ticks to calculate 6 moving averages
//        send(createTick());
        send(createTickBuyLow3());
        send(createTickBuyLow3());
        send(createTickBuyLow2());
        send(createTickBuyLow2());
        send(createTickBuyLow1());
        send(createTickBuyLow1());
        // one last tick
        send(createTickBuyLow1());

        // Assert to check we have created 3 child orders under good buy conditions
        assertEquals(3, container.getState().getChildOrders().size());
        assertEquals(3, container.getState().getActiveChildOrders().size());
        assertTrue(container.getState().getActiveChildOrders().stream().allMatch(childOrder -> childOrder.getSide().toString().equals("SELL")));

        // send a tick that would fill out orders
        send(createTickFillSELLOrders());

        //then: get the state
        var state = container.getState();

        //Check things like filled quantity, cancelled order count etc....
        long filledQuantity = state.getChildOrders().stream().map(ChildOrder::getFilledQuantity).reduce(Long::sum).get();
        //and: check that our algo state was updated to reflect our fills when the market data
        assertEquals(300, filledQuantity);
    }

}