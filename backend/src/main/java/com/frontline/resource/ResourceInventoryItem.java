package com.frontline.resource;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.frontline.model.Customer;
import com.frontline.model.Resource;
import java.time.Instant;

public record ResourceInventoryItem(
        @JsonUnwrapped Resource resource, Long visitId, String ticket, Customer customer,
        Long visitVersion, Instant assignedAt
) {}
