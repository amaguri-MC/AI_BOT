package com.amaguri.aibuddy.brain;

import java.util.List;

/** 1人分の人格。personas/*.json から読み込む。 */
public record Persona(String id, String displayName, String skin, String systemPrompt, List<String> fallbackLines) {
}
