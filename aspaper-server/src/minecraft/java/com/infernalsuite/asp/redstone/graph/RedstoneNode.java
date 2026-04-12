package com.infernalsuite.asp.redstone.graph;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;

public class RedstoneNode {
    private final BlockPos position;
    private int powerState;
    private final List<RedstoneEdge> incomingEdges = new ArrayList<>();
    private final List<RedstoneEdge> outgoingEdges = new ArrayList<>();
    private final NodeType type;

    public enum NodeType {
        SOURCE, SINK, WIRE, REPEATER, COMPARATOR, OBSERVER
    }

    public RedstoneNode(BlockPos position, NodeType type) {
        this.position = position;
        this.type = type;
        this.powerState = 0;
    }

    public BlockPos getPosition() {
        return position;
    }

    public int getPowerState() {
        return powerState;
    }

    public void setPowerState(int powerState) {
        this.powerState = powerState;
    }

    public NodeType getType() {
        return type;
    }

    public void addIncomingEdge(RedstoneEdge edge) {
        incomingEdges.add(edge);
    }

    public void addOutgoingEdge(RedstoneEdge edge) {
        outgoingEdges.add(edge);
    }

    public List<RedstoneEdge> getIncomingEdges() {
        return incomingEdges;
    }

    public List<RedstoneEdge> getOutgoingEdges() {
        return outgoingEdges;
    }
}
