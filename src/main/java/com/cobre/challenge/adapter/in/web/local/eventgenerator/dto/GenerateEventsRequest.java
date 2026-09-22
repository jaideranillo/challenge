package com.cobre.challenge.adapter.in.web.local.eventgenerator.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** {@code POST /local/event-generator/generate} request body: how many synthetic events to emit. */
public record GenerateEventsRequest(@NotNull @Min(1) @Max(1000) Integer count) {}
