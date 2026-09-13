package ac.cult.cultac.bedrock.prediction.geometry;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;

public final class BedrockPositionTranslator {
    public static final double PLAYER_PACKET_Y_OFFSET = 1.6200103759765625D;

    private BedrockPositionTranslator() {
    }

    public static Vec3d physicalFeetToPacketPosition(Vec3d physicalFeetPosition) {
        return roundTripPacketPosition(new Vec3d(
            physicalFeetPosition.x(),
            physicalFeetPosition.y() + PLAYER_PACKET_Y_OFFSET,
            physicalFeetPosition.z()
        ));
    }

    public static Vec3d packetPositionToPhysicalFeet(Vec3d packetPosition) {
        Vec3d rounded = roundTripPacketPosition(packetPosition);
        return new Vec3d(rounded.x(), rounded.y() - PLAYER_PACKET_Y_OFFSET, rounded.z());
    }

    public static Vec3d normalizePhysicalFeetPosition(Vec3d physicalFeetPosition) {
        return packetPositionToPhysicalFeet(physicalFeetToPacketPosition(physicalFeetPosition));
    }

    public static Vec3d physicalFeetToPacketPosition(Vec3d worldFeet, BedrockCoordinateFrame frame) {
        return physicalFeetToPacketPosition(frame.toLocal(worldFeet));
    }

    public static Vec3d packetPositionToPhysicalFeet(Vec3d localPacket, BedrockCoordinateFrame frame) {
        return frame.toWorld(packetPositionToPhysicalFeet(localPacket));
    }

    public static Vec3d normalizePhysicalFeetPosition(Vec3d worldFeet, BedrockCoordinateFrame frame) {
        return frame.toWorld(normalizePhysicalFeetPosition(frame.toLocal(worldFeet)));
    }

    public static Vec3d roundTripPacketPosition(Vec3d position) {
        return new Vec3d(packetFloatToDouble(position.x()), packetFloatToDouble(position.y()), packetFloatToDouble(position.z()));
    }

    public static double packetFloatToDouble(double value) {
        return (double) (float) value;
    }
}
