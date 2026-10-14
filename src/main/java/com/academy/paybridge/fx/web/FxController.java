package com.academy.paybridge.fx.web;

import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.partner.api.CurrentPartner;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/partner/fx")
@Tag(name = "Partner FX")
public class FxController {

    private final FxApi fxApi;

    public FxController(FxApi fxApi) {
        this.fxApi = fxApi;
    }

    @GetMapping("/rate")
    @Operation(summary = "Indicative rate (not guaranteed)",
            description = "Shows the mid-market rate, our spread, and the rate a sender would get. "
                    + "Only a quote locks a rate.")
    public RateResponse rate(
            @Parameter(example = "USD") @RequestParam Currency from,
            @Parameter(example = "NGN") @RequestParam Currency to) {
        return RateResponse.from(fxApi.currentRate(from, to));
    }

    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a quote that locks the rate until it expires",
            description = "The quote is valid for a short time (see expiresAt) and can be used once.")
    public QuoteResponse createQuote(@Valid @RequestBody CreateQuoteRequest request) {
        Money send = Money.ofMajor(request.sendAmount(), request.sendCurrency());
        return QuoteResponse.from(fxApi.createQuote(CurrentPartner.id(), send, request.receiveCurrency()));
    }

    @GetMapping("/quotes/{quoteId}")
    @Operation(summary = "Get one of your quotes and its current status (ACTIVE, USED or EXPIRED)")
    public QuoteResponse getQuote(@PathVariable UUID quoteId) {
        return QuoteResponse.from(fxApi.getQuote(quoteId, CurrentPartner.id()));
    }
}