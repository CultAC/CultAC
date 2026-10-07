/* Copyright (C) 2025 retrooper and contributors. SPDX-License-Identifier: GPL-3.0-or-later
 * Easing equations adapted from PacketEvents' EasingFunctions, with pinned client arithmetic. */
package ac.cult.blocksim.environment;

import ac.cult.blocksim.engine.VanillaMath;
import com.google.gson.JsonElement;

/** Compiled easing for received boolean timelines; visual attributes are not simulated. */
@FunctionalInterface
public interface ClientEasing {
    float apply(float progress);

    static ClientEasing read(JsonElement value) {
        if (value == null) return x -> x;
        if (value.isJsonObject()) {
            var controls = value.getAsJsonObject().getAsJsonArray("cubic_bezier");
            if (controls.size() != 4) throw new IllegalArgumentException("Cubic easing requires four controls");
            float x1 = controls.get(0).getAsFloat(), y1 = controls.get(1).getAsFloat();
            float x2 = controls.get(2).getAsFloat(), y2 = controls.get(3).getAsFloat();
            if (x1 < 0 || x1 > 1 || x2 < 0 || x2 > 1) throw new IllegalArgumentException("Cubic x controls must be in [0,1]");
            var cx = coefficients(x1, x2); var cy = coefficients(y1, y2);
            return x -> polynomial(cy, solve(cx, x));
        }
        String name = value.getAsString();
        return switch (name) {
            case "constant" -> x -> 0;
            case "linear" -> x -> x;
            case "in_back" -> x -> square(x) * (2.70158F * x - 1.70158F);
            case "in_bounce" -> x -> 1.0F - bounce(1.0F - x);
            case "in_circ" -> x -> (float) -Math.sqrt(1.0F - x * x) + 1.0F;
            case "in_cubic" -> ClientEasing::cube;
            case "in_elastic" -> x -> x == 0 ? 0 : x == 1 ? 1 : (float) (-Math.pow(2.0, 10.0 * x - 10.0) * Math.sin((x * 10.0 - 10.75) * (float) (Math.PI * 2 / 3)));
            case "in_expo" -> x -> x == 0 ? 0 : (float) Math.pow(2.0, 10.0 * x - 10.0);
            case "in_quad" -> ClientEasing::square;
            case "in_quart" -> x -> square(square(x));
            case "in_quint" -> x -> square(square(x)) * x;
            case "in_sine" -> x -> 1.0F - VanillaMath.cos(x * (float) (Math.PI / 2));
            case "out_back" -> x -> 1.0F + 2.70158F * cube(x - 1.0F) + 1.70158F * square(x - 1.0F);
            case "out_bounce" -> ClientEasing::bounce;
            case "out_circ" -> x -> (float) Math.sqrt(1.0F - square(x - 1.0F));
            case "out_cubic" -> x -> 1.0F - cube(1.0F - x);
            case "out_elastic" -> x -> x == 0 ? 0 : x == 1 ? 1 : (float) (Math.pow(2.0, -10.0 * x) * Math.sin((x * 10.0 - 0.75) * (float) (Math.PI * 2 / 3)) + 1.0);
            case "out_expo" -> x -> x == 1 ? 1 : 1.0F - (float) Math.pow(2.0, -10.0 * x);
            case "out_quad" -> x -> 1.0F - square(1.0F - x);
            case "out_quart" -> x -> 1.0F - square(square(1.0F - x));
            case "out_quint" -> x -> 1.0F - (float) Math.pow(1.0 - x, 5.0);
            case "out_sine" -> x -> VanillaMath.sin(x * (float) (Math.PI / 2));
            case "in_out_back" -> x -> x < .5F ? 4.0F * x * x * (7.189819F * x - 2.5949094F) / 2.0F : (square(2.0F * x - 2.0F) * (3.5949094F * (2.0F * x - 2.0F) + 2.5949094F) + 2.0F) / 2.0F;
            case "in_out_bounce" -> x -> x < .5F ? (1.0F - bounce(1.0F - 2.0F * x)) / 2.0F : (1.0F + bounce(2.0F * x - 1.0F)) / 2.0F;
            case "in_out_circ" -> x -> x < .5F ? (float) ((1.0 - Math.sqrt(1.0 - Math.pow(2.0 * x, 2.0))) / 2.0) : (float) ((Math.sqrt(1.0 - Math.pow(-2.0 * x + 2.0, 2.0)) + 1.0) / 2.0);
            case "in_out_cubic" -> x -> x < .5F ? 4.0F * cube(x) : (float) (1.0 - Math.pow(-2.0 * x + 2.0, 3.0) / 2.0);
            case "in_out_elastic" -> ClientEasing::inOutElastic;
            case "in_out_expo" -> x -> x < .5F ? x == 0 ? 0 : (float) (Math.pow(2.0, 20.0 * x - 10.0) / 2.0) : x == 1 ? 1 : (float) ((2.0 - Math.pow(2.0, -20.0 * x + 10.0)) / 2.0);
            case "in_out_quad" -> x -> x < .5F ? 2.0F * square(x) : (float) (1.0 - Math.pow(-2.0 * x + 2.0, 2.0) / 2.0);
            case "in_out_quart" -> x -> x < .5F ? 8.0F * square(square(x)) : (float) (1.0 - Math.pow(-2.0 * x + 2.0, 4.0) / 2.0);
            case "in_out_quint" -> x -> x < .5F ? 16.0F * x * x * x * x * x : (float) (1.0 - Math.pow(-2.0 * x + 2.0, 5.0) / 2.0);
            case "in_out_sine" -> x -> -(VanillaMath.cos((float) Math.PI * x) - 1.0F) / 2.0F;
            default -> throw new IllegalArgumentException("Unknown client easing " + name);
        };
    }
    private static float square(float x) { return x * x; }
    private static float cube(float x) { return x * x * x; }
    private static float bounce(float x) {
        if (x < 0.36363637F) return 7.5625F * square(x);
        if (x < 0.72727275F) return 7.5625F * square(x - 0.54545456F) + 0.75F;
        if (x < 0.9090909090909091) return 7.5625F * square(x - 0.8181818F) + 0.9375F;
        return 7.5625F * square(x - 0.95454544F) + 0.984375F;
    }
    private static float inOutElastic(float x) {
        if (x == 0) return 0;
        if (x == 1) return 1;
        float frequency = (float) Math.PI * 4.0F / 9.0F;
        double sin = Math.sin((20.0 * x - 11.125) * frequency);
        return x < .5F ? (float) (-(Math.pow(2.0, 20.0 * x - 10.0) * sin) / 2.0) : (float) (Math.pow(2.0, -20.0 * x + 10.0) * sin / 2.0 + 1.0);
    }
    private static float[] coefficients(float first, float second) {
        return new float[]{3.0F * first - 3.0F * second + 1.0F, -6.0F * first + 3.0F * second, 3.0F * first};
    }
    private static float polynomial(float[] c, float t) { return ((c[0] * t + c[1]) * t + c[2]) * t; }
    private static float solve(float[] c, float x) {
        float t = x;
        for (int iteration = 0; iteration < 4; iteration++) {
            float error = polynomial(c, t) - x;
            if (Math.abs(error) < 1.0E-5F) return t;
            float gradient = (3.0F * c[0] * t + 2.0F * c[1]) * t + c[2];
            if (gradient < 1.0E-5F) break;
            float step = error / gradient;
            t -= step < -.25F ? -.25F : Math.min(step, .25F);
        }
        float min = 0, max = 1;
        while (min < max) {
            float error = polynomial(c, t) - x;
            if (Math.abs(error) < 1.0E-5F) return t;
            if (error < 0) min = t; else max = t;
            t = (max + min) / 2.0F;
        }
        return t;
    }
}
