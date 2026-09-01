package ac.grim.grimac.parity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Shared deterministic JSON codec for parity artifacts. */
public final class ParityJson {
    public static final Gson CODEC = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();
    /** JSONL artifacts must contain exactly one complete value per line. */
    public static final Gson JSONL_CODEC = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private ParityJson() {
    }
}
