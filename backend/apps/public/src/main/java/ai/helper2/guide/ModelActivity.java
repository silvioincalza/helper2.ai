package ai.helper2.guide;

import java.util.function.Supplier;

public final class ModelActivity {

  private static final ScopedValue<GuideProgress> WATCHER = ScopedValue.newInstance();

  private ModelActivity() {}

  public static <T> T reportedTo(GuideProgress progress, Supplier<T> work) {
    return ScopedValue.where(WATCHER, progress).call(work::get);
  }

  public static GuideProgress watcher() {
    return WATCHER.isBound() ? WATCHER.get() : GuideProgress.NONE;
  }
}
