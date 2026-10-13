package com.academy.paybridge.customer.api;

import java.util.Optional;

public interface CustomerApi {

    /** Every customer API key starts with this, so keys are recognisable and routable. */
    String API_KEY_PREFIX = "pbk_";


    RegisteredCustomer register(String fullName, String email);

    /** Empty if the key is unknown or the customer is suspended. */
    Optional<CustomerView> authenticate(String apiKey);
}