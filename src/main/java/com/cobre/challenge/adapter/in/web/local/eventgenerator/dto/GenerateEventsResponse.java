package com.cobre.challenge.adapter.in.web.local.eventgenerator.dto;

import java.util.List;

/** Ids of the synthetic events just registered, in emission order. */
public record GenerateEventsResponse(int requested, int generated, List<String> eventIds) {}
