package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.player.GrimPlayer;

@CheckData(name = "Hitboxes", stableKey = "grim.combat.hitboxes", description = "Tried to hit an entity outside its valid hitbox")
public class Hitboxes extends Check implements CheckListener {
    public Hitboxes(GrimPlayer player) {
        super(player);
    }
}
