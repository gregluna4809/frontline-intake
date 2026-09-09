package com.frontline.visit;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.frontline.model.Customer;
import com.frontline.model.Visit;
import com.frontline.model.Resource;
import java.time.Instant;

public record VisitQueueItem(
        @JsonUnwrapped Visit visit, Customer customer, Instant waitingSince, Resource.Type requiredResourceType
) {}
