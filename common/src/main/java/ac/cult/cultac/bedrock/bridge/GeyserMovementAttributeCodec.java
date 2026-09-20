package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.prediction.state.BedrockMovementAttributeState;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.data.attribute.AttributeModifierData;
import org.cloudburstmc.protocol.bedrock.data.attribute.AttributeOperation;

final class GeyserMovementAttributeCodec {
    private GeyserMovementAttributeCodec() { }

    static BedrockMovementAttributeState capture(AttributeData value) {
        return new BedrockMovementAttributeState(value.getValue(), value.getMinimum(), value.getMaximum(),
                value.getDefaultMinimum(), value.getDefaultMaximum(), value.getDefaultValue(),
                value.getModifiers().stream().map(modifier -> new BedrockMovementAttributeState.Modifier(
                    modifier.getId(), modifier.getName(), modifier.getAmount(), modifier.getOperation().ordinal(),
                    modifier.getOperand(), modifier.isSerializable())).toList());
    }

    static AttributeData encode(BedrockMovementAttributeState value) {
        return new AttributeData("minecraft:movement", value.minimum(), value.maximum(), value.current(),
                value.defaultMinimum(), value.defaultMaximum(), value.defaultValue(),
                value.modifiers().stream().map(modifier -> new AttributeModifierData(modifier.id(), modifier.name(),
                    modifier.amount(), AttributeOperation.values()[modifier.operation()], modifier.operand(), modifier.serializable())).toList());
    }
}
