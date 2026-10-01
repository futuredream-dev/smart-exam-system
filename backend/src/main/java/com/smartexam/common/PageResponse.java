package com.smartexam.common;

import java.util.List;

/** One-based page; total counts all matching rows before LIMIT/OFFSET. */
public record PageResponse<T>(List<T> items, long total, int page, int size) {}
