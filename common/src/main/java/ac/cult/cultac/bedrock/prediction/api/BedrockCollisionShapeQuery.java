package ac.cult.cultac.bedrock.prediction.api;

import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import java.util.Optional;

public record BedrockCollisionShapeQuery(
    Optional<WorldCollisionBox> actorCollisionQuery,
    boolean scaffoldingPassThrough,
    boolean leatherBoots,
    float fallDistance
) {
    public static final BedrockCollisionShapeQuery NONE =
        new BedrockCollisionShapeQuery(Optional.empty(), false, false, 0.0F);

    public BedrockCollisionShapeQuery {
        actorCollisionQuery = actorCollisionQuery == null ? Optional.empty() : actorCollisionQuery;
        if (!Float.isFinite(fallDistance) || fallDistance < 0.0F) {
            throw new IllegalArgumentException("fallDistance must be finite and non-negative");
        }
    }

    // Client-reported movement outputs are not collision context.
    public static BedrockCollisionShapeQuery actor(
        WorldCollisionBox actorCollisionQuery,
        boolean scaffoldingPassThrough,
        boolean leatherBoots,
        float fallDistance
    ) {
        return new BedrockCollisionShapeQuery(
            Optional.ofNullable(actorCollisionQuery),
            scaffoldingPassThrough,
            leatherBoots,
            fallDistance
        );
    }

    public static BedrockCollisionShapeQuery actor(
        WorldCollisionBox actorCollisionQuery,
        boolean scaffoldingPassThrough,
        boolean leatherBoots
    ) {
        return actor(actorCollisionQuery, scaffoldingPassThrough, leatherBoots, 0.0F);
    }
}
