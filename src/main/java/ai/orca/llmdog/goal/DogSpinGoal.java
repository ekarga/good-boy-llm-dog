package ai.orca.llmdog.goal;

import ai.orca.llmdog.entity.LlmDogEntity;
import net.minecraft.entity.ai.goal.Goal;

import java.util.EnumSet;

public class DogSpinGoal extends Goal {
    private final LlmDogEntity dog;

    public DogSpinGoal(LlmDogEntity dog) {
        this.dog = dog;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        return dog.isSpinning();
    }

    @Override
    public boolean shouldContinue() {
        return dog.isSpinning();
    }

    @Override
    public void start() {
        dog.getNavigation().stop();
    }

    @Override
    public void stop() {
        dog.getNavigation().stop();
    }
}
