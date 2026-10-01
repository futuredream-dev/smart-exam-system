package com.smartexam.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AiAssemblyRequests {
    private AiAssemblyRequests() {}

    public record Interpret(@NotBlank @Size(max = 2000) String requirement) {}
    public record Recommend(@NotBlank @Size(max = 2000) String requirement, @NotNull JsonNode intent,
                            @NotEmpty @Size(max = 100) @Valid List<RuleAssemblyRequests.RuleBlock> rules) {}
}
