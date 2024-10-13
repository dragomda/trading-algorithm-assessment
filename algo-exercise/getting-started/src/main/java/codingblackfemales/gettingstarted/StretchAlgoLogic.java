package codingblackfemales.gettingstarted;

import codingblackfemales.action.Action;
import codingblackfemales.action.CancelChildOrder;
import codingblackfemales.action.CreateChildOrder;
import codingblackfemales.action.NoAction;
import codingblackfemales.algo.AlgoLogic;
import codingblackfemales.sotw.ChildOrder;
import codingblackfemales.sotw.SimpleAlgoState;
import codingblackfemales.sotw.marketdata.AskLevel;
import codingblackfemales.sotw.marketdata.BidLevel;
import codingblackfemales.util.Util;
import messages.order.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class StretchAlgoLogic implements AlgoLogic {
    final int MINIMUM_ORDER_BOOKS = 6; //
    final int MAX_CHILD_ORDERS = 3;
    long childOrderQuantity = 100;
    long parentOrderQuantity = 300; // buy or sell 300 shares (3 child orders of a 100)
    final double TREND_THRESHOLD = 0.5; // use this threshold to avoid algo reacting to small fluctuations in price - prices can only be long so 0.5 is a good measure for a stable market
    // initialise the moving averages list as instance fields to allow the evolute method to accumulate them over several ticks
    private List<Double> bidAverages = new ArrayList<>();
    private List<Double> askAverages = new ArrayList<>();
    private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(8, 0, 0);
    private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(16, 35, 0); // market close time is after close market auction window to allow our algo to secure a good ask/bid price
    private static final ZoneId LONDON_TIME_ZONE = ZoneId.of("Europe/London");

    /*
    * This Algo logic builds on the basic algo logic by adding orders on the BUY side when favours buying low (sellers are placing lower ask offers than historic data) AND THEN when the market favours selling at a higher price (ask price are going back up), place a SELL order that purchases those 300 shares previously bought at a higher price
    * The Market trend is determined by
    * */

    private static final Logger logger = LoggerFactory.getLogger(StretchAlgoLogic.class);

    @Override
    public Action evaluate(SimpleAlgoState state) {

        var orderBookAsString = Util.orderBookToString(state);
        logger.info("[STRETCH-ALGO] The state of the order book is:\n{}", orderBookAsString);

        List<ChildOrder> allChildOrders = state.getChildOrders(); // list of all child orders (active and non-active)
        long totalFilledQuantity = allChildOrders.stream().mapToLong(ChildOrder::getFilledQuantity).sum(); // sum of quantities of all filled orders
        List<ChildOrder> activeChildOrders = state.getActiveChildOrders(); // list of all child orders (active and non-active)

        if(isMarketClosed()) {
            for (ChildOrder activeChildOrder : activeChildOrders) {
                if (activeChildOrder.getFilledQuantity() != childOrderQuantity  && isMarketClosed() && !activeChildOrders.isEmpty()) {
                    logger.info("[STRETCH-ALGO] The market is closed, cancelling orders ");
                    logger.info("[STRETCH-ALGO] Cancelling day order ID: {} on side: {}", activeChildOrder.getOrderId(), activeChildOrder.getSide());
                    logger.info("[STRETCH-ALGO] Order State: {}", activeChildOrder.getState());
                    return new CancelChildOrder(activeChildOrder);
                }
            }
            logger.info("[STRETCH-ALGO] No active orders & Market is closed, Not placing new orders ");
            return NoAction.NoAction;
        }

        // Return No action if max count of child orders created or parent order filled
        if (allChildOrders.size() >= MAX_CHILD_ORDERS || totalFilledQuantity >= parentOrderQuantity) {
            logger.info("[STRETCH-ALGO] Maximum number of orders: {} reached OR parent desired quantity has been filled {}. Returning No Action.", allChildOrders.size(), totalFilledQuantity);
            return NoAction.NoAction;
        }

        // 1. calc the average for each order book --> add that to a list --> add to a list of moving averages --> once list.size() == 6 --> evaluate trend -->
        List<OrderBookLevel> bidLevels = getOrderBookLevels(state).get("Bid");
        List<OrderBookLevel> askLevels = getOrderBookLevels(state).get("Ask");
        double bidMovingWeightAverage = calculateMovingWeightAverage(bidLevels);
        double askMovingWeightAverage = calculateMovingWeightAverage(askLevels);
        bidAverages.add(bidMovingWeightAverage);
        askAverages.add(askMovingWeightAverage);

        if (bidAverages.size() < MINIMUM_ORDER_BOOKS || askAverages.size() < MINIMUM_ORDER_BOOKS) {
            logger.info("[STRETCH-ALGO] Insufficient Moving weight averages to evaluate the market trend, there are currently {} bids averages and {} asks averages", bidAverages.size(), askAverages.size());
            return NoAction.NoAction;
        }

        logger.info("[STRETCH-ALGO] Enough moving weight averages to evaluate market trend, calculating market trend for both sides");
        double bidMarketTrend = evaluateTrendUsingMWAList(bidAverages);
        double askMarketTrend = evaluateTrendUsingMWAList(askAverages);
        logger.info("[STRETCH-ALGO] Market trend calculated, Bid market trend is {}, Ask Market Trend is {}", bidMarketTrend, askMarketTrend);

        final long highestAsk = askLevels.stream().mapToLong(level -> level.price).max().orElse(0);
        logger.info("[STRETCH-ALGO] FilledQuantity Tracker: Total Filled Quantity for orders so far is: {}", totalFilledQuantity);

        // buy now before bid prices increase further and whilst ask side is offering lower and lower offers
        if (Math.abs(askMarketTrend) >  TREND_THRESHOLD ) {
            logger.info("[STRETCH-ALGO] Trend favorable for BUY, placing child order.");
            final BidLevel bestBid = state.getBidAt(0);
            long price = bestBid.price;
            logger.info("Best bid: {}", price);
            return new CreateChildOrder(Side.BUY, childOrderQuantity, price);
        } else if (Math.abs(askMarketTrend) >  TREND_THRESHOLD && Math.abs(askMarketTrend) > Math.abs(bidMarketTrend)) { // sell what you originally bought for more by checking ask side trend
            logger.info("[STRETCH-ALGO] Trend favorable for SELL, placing child order.");
            logger.info("Best ask: {}", highestAsk);
            return new CreateChildOrder(Side.SELL, childOrderQuantity, highestAsk);
        } else if (bidMarketTrend <= TREND_THRESHOLD && askMarketTrend <= TREND_THRESHOLD) { // stable criteria
            logger.info("[STRETCH-ALGO] Market is stable, holding off placing orders for the meantime. Returning No Action.");
            return NoAction.NoAction;
        } // // profit == bestBid - lowest ask offer

        logger.info("[STRETCH-ALGO] No action to take.");
        return NoAction.NoAction;
    }

    /* Method 2: Determine if Market is closed to cancel day orders */
    public boolean isMarketClosed() {
        ZonedDateTime timeNow = ZonedDateTime.now(LONDON_TIME_ZONE); // Define London time zone & the present time
        LocalDate today = LocalDate.now(LONDON_TIME_ZONE); // Declare today's date according to London's time zone
        ZonedDateTime marketOpenDateTime = ZonedDateTime.of(today, MARKET_OPEN_TIME, LONDON_TIME_ZONE);  // Declare market opening conditions
        ZonedDateTime marketCloseDateTime = ZonedDateTime.of(today, MARKET_CLOSE_TIME, LONDON_TIME_ZONE); // Declare market closing conditions

        // Deduce if the current time is before opening, after closing, or on a weekend - we will ignore holidays for now (could create a list/map of holiday dates using some sort of library)
        return timeNow.isBefore(marketOpenDateTime) || timeNow.isAfter(marketCloseDateTime) || today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY; // Market is closed @ or after 4.30pm
    }

    public HashMap<String, List<OrderBookLevel>> getOrderBookLevels(SimpleAlgoState state) {
        List<OrderBookLevel> bidMarketOrders = new ArrayList<>(); // initialise an empty list of orderLevel Objects
        List<OrderBookLevel> askMarketOrders = new ArrayList<>(); // initialise an empty list of orderLevel Objects

        int maxCountOfLevels = Math.max(state.getAskLevels(), state.getBidLevels()); // get max number of levels in order book

        for (int i = 0; i < maxCountOfLevels; i++) {
            if (state.getBidLevels() > i) { // if there are bid orders --> get the first level price & quantity
                BidLevel bidLevel = state.getBidAt(i);
                bidMarketOrders.add(new OrderBookLevel(bidLevel.price, bidLevel.quantity)); // Create a new OrderBookLevel for bid side
            }
            if (state.getAskLevels() > i) { // if there are ask orders --> get the first level price & quantity
                AskLevel askLevel = state.getAskAt(i);
                askMarketOrders.add(new OrderBookLevel(askLevel.price, askLevel.quantity)); // Create a new OrderBookLevel for ask side
            }
        }
        // to allow us to return both list sin the same method - a hashmap will be used (time complexity of o(1) - easier to lookup
        HashMap<String, List<OrderBookLevel>> orderBookMap = new HashMap<>();
        orderBookMap.put("Bid", bidMarketOrders);
        orderBookMap.put("Ask", askMarketOrders);

        return orderBookMap;
    }

    public class OrderBookLevel {
        public final long price;
        public final long quantity;

        public OrderBookLevel(long price, long quantity) {
            this.price = price;
            this.quantity = quantity;
        }
    }

    // Method 3: Evaluate trend based on the list of averages we have on the most recent 6 instances of historical data
    public double evaluateTrendUsingMWAList(List<Double> listOfAverages) {
        double sumOfDifferences = 0;

        if (!listOfAverages.isEmpty()) {
            for (int i = 0; i < listOfAverages.size() - 1; i++) {
                double differenceInTwoAverages = listOfAverages.get(i + 1) - listOfAverages.get(i);
                sumOfDifferences += differenceInTwoAverages;
            }
        }
        return sumOfDifferences;
    }

    public double calculateMovingWeightAverage(List<OrderBookLevel> OrderBookLevel) {
        long totalQuantityAccumulated = 0;
        double weightedSum = 0;
        double weightedAverage;
        // Loop over OrderBooks instead of levels in 1 OrderBook! 6 averages are calculated in this method
        for (OrderBookLevel order : OrderBookLevel) {
            totalQuantityAccumulated += order.quantity;
            weightedSum += (order.price * order.quantity);
        }
        weightedAverage = Math.round((weightedSum / totalQuantityAccumulated) * 100.0) / 100.0;
        return totalQuantityAccumulated > 0 ? weightedAverage : 0;
    }
}