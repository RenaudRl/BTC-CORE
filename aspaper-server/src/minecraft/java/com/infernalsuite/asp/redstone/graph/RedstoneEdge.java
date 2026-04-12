package com.infernalsuite.asp.redstone.graph;

public class RedstoneEdge {
    private final RedstoneNode source;
    private final RedstoneNode target;
    private final int weight;

    public RedstoneEdge(RedstoneNode source, RedstoneNode target, int weight) {
        this.source = source;
        this.target = target;
        this.weight = weight;
    }

    public RedstoneNode getSource() {
        return source;
    }

    public RedstoneNode getTarget() {
        return target;
    }

    public int getWeight() {
        return weight;
    }
}
