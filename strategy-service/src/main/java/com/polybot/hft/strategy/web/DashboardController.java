package com.polybot.hft.strategy.web;

import com.polybot.hft.config.HftProperties;
import com.polybot.hft.polymarket.strategy.GabagoolDirectionalEngine;
import com.polybot.hft.polymarket.strategy.config.GabagoolConfig;
import com.polybot.hft.polymarket.strategy.model.MarketInventory;
import com.polybot.hft.polymarket.strategy.model.OrderState;
import com.polybot.hft.polymarket.strategy.service.QuoteCalculator;
import com.polybot.hft.polymarket.ws.TopOfBook;
import com.polybot.hft.polymarket.ws.ClobMarketWebSocketClient;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private final @NonNull HftProperties properties;
    private final @NonNull GabagoolDirectionalEngine gabagoolEngine;
    private final @NonNull ClobMarketWebSocketClient marketWs;
    private final @NonNull QuoteCalculator quoteCalculator;

    @GetMapping("/")
    public void redirect(HttpServletResponse response) throws Exception {
        response.sendRedirect("/dashboard.html");
    }

    @GetMapping("/data")
    public ResponseEntity<DashboardData> dashboard() {
        GabagoolConfig cfg = GabagoolConfig.from(properties.strategy().gabagool());

        Instant now = Instant.now();

        // Strategy basics
        boolean running = gabagoolEngine.isRunning();
        String mode = properties.mode().name();
        int activeMarketCount = gabagoolEngine.activeMarketCount();
        int wsSubscribed = marketWs.subscribedAssetCount();
        boolean wsStarted = marketWs.isStarted();

        // Bankroll
        BigDecimal bankrollUsd = cfg.bankrollUsd();

        // Config summary
        ConfigInfo configInfo = new ConfigInfo(
            cfg.completeSetMinEdge(),
            cfg.completeSetMaxSkewTicks(),
            cfg.completeSetTopUpEnabled(),
            cfg.completeSetTopUpSecondsToEnd(),
            cfg.completeSetFastTopUpEnabled(),
            cfg.takerModeEnabled(),
            cfg.refreshMillis(),
            cfg.minReplaceMillis(),
            cfg.maxSecondsToEnd(),
            cfg.maxOrderBankrollFraction(),
            cfg.maxTotalBankrollFraction(),
            cfg.bankrollTradingFraction()
        );

        // Gather market/order/inventory data
        List<MarketInfo> markets = gabagoolEngine.getMarkets().stream()
            .map(m -> {
                long secondsToEnd = Duration.between(now, m.endTime()).getSeconds();
                String timeToEndStr = formatDuration(secondsToEnd);

                // Get TOB
                TopOfBook upBook = marketWs.getTopOfBook(m.upTokenId());
                TopOfBook downBook = marketWs.getTopOfBook(m.downTokenId());

                BigDecimal upBid = upBook != null ? upBook.bestBid() : null;
                BigDecimal upAsk = upBook != null ? upBook.bestAsk() : null;
                BigDecimal downBid = downBook != null ? downBook.bestBid() : null;
                BigDecimal downAsk = downBook != null ? downBook.bestAsk() : null;

                // Edge calculation
                BigDecimal completeSetEdge = null;
                if (upBid != null && downBid != null) {
                    completeSetEdge = BigDecimal.ONE.subtract(upBid.add(downBid))
                        .setScale(4, RoundingMode.HALF_UP);
                }

                // Get orders
                OrderState upOrder = gabagoolEngine.getOrder(m.upTokenId());
                OrderState downOrder = gabagoolEngine.getOrder(m.downTokenId());

                OrderInfo upOrderInfo = orderToInfo(upOrder, now);
                OrderInfo downOrderInfo = orderToInfo(downOrder, now);

                // Inventory
                MarketInventory inv = gabagoolEngine.getInventory(m.slug());
                BigDecimal upShares = inv != null ? inv.upShares() : BigDecimal.ZERO;
                BigDecimal downShares = inv != null ? inv.downShares() : BigDecimal.ZERO;
                BigDecimal imbalance = inv != null ? inv.imbalance() : BigDecimal.ZERO;
                BigDecimal totalShares = inv != null ? inv.totalShares() : BigDecimal.ZERO;

                // PnL estimate from inventory (if we have positions at bids)
                BigDecimal unrealizedPnl = null;
                if (upBid != null && downBid != null && totalShares.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal cost = upShares.add(downShares);
                    BigDecimal value = upShares.multiply(upBid).add(downShares.multiply(downBid));
                    unrealizedPnl = value.subtract(cost).setScale(2, RoundingMode.HALF_UP);
                }

                return new MarketInfo(
                    m.slug(),
                    m.marketType(),
                    secondsToEnd,
                    timeToEndStr,
                    upBid, upAsk,
                    downBid, downAsk,
                    completeSetEdge,
                    upShares, downShares, imbalance, totalShares,
                    upOrderInfo, downOrderInfo,
                    unrealizedPnl
                );
            })
            .sorted(Comparator.comparingLong(MarketInfo::secondsToEnd))
            .toList();

        // Recent orders (from all open orders, sorted by placement time)
        List<OrderInfo> recentOrders = gabagoolEngine.getAllOrders().stream()
            .map(o -> orderToInfo(o, now))
            .sorted(Comparator.comparing(OrderInfo::ageSeconds).reversed())
            .limit(50)
            .toList();

        return ResponseEntity.ok(new DashboardData(
            running, mode, activeMarketCount, wsSubscribed, wsStarted,
            bankrollUsd, configInfo, markets, recentOrders
        ));
    }

    private OrderInfo orderToInfo(OrderState order, Instant now) {
        if (order == null) return null;
        long ageSeconds = Duration.between(order.placedAt(), now).getSeconds();
        return new OrderInfo(
            order.orderId(),
            order.tokenId(),
            order.direction().name(),
            order.price(),
            order.size(),
            ageSeconds,
            order.placedAt().toString(),
            order.matched() != null ? order.matched() : BigDecimal.ZERO
        );
    }

    private static String formatDuration(long seconds) {
        if (seconds < 0) return "Expired";
        if (seconds < 60) return seconds + "s";
        long mins = seconds / 60;
        long secs = seconds % 60;
        return mins + "m " + secs + "s";
    }

    // Record types
    public record DashboardData(
        boolean running,
        String mode,
        int activeMarketCount,
        int wsSubscribedAssets,
        boolean wsStarted,
        BigDecimal bankrollUsd,
        ConfigInfo config,
        List<MarketInfo> markets,
        List<OrderInfo> recentOrders
    ) {}

    public record ConfigInfo(
        double completeSetMinEdge,
        int completeSetMaxSkewTicks,
        boolean topUpEnabled,
        long topUpSecondsToEnd,
        boolean fastTopUpEnabled,
        boolean takerModeEnabled,
        long refreshMillis,
        long minReplaceMillis,
        long maxSecondsToEnd,
        double maxOrderBankrollFraction,
        double maxTotalBankrollFraction,
        double bankrollTradingFraction
    ) {}

    public record MarketInfo(
        String slug,
        String marketType,
        long secondsToEnd,
        String timeToEnd,
        BigDecimal upBid,
        BigDecimal upAsk,
        BigDecimal downBid,
        BigDecimal downAsk,
        BigDecimal completeSetEdge,
        BigDecimal upShares,
        BigDecimal downShares,
        BigDecimal imbalance,
        BigDecimal totalShares,
        OrderInfo upOrder,
        OrderInfo downOrder,
        BigDecimal unrealizedPnl
    ) {}

    public record OrderInfo(
        String orderId,
        String tokenId,
        String direction,
        BigDecimal price,
        BigDecimal size,
        long ageSeconds,
        String placedAt,
        BigDecimal matched
    ) {}
}
