package com.async.request.reply.adapter.in.web.dto;

import com.async.request.reply.core.enums.JobStatus;

public record JobNotCompletedResponse(String error, JobStatus status) {}
