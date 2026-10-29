package dev.litextras.core;

import net.minecraft.block.BlockState;

import java.util.List;

/** Ergebnis des Soll/Ist-Vergleichs für EINE Position. Wird von allen Anzeigen gemeinsam genutzt. */
public record CompareResult(Status status, BlockState soll, BlockState ist, List<PropDiff> diffs, String groupName) {

    public enum Status {
        /** Keine Schematic hier und auch kein störender Block. */
        NONE,
        /** Exakt richtig (ignorierte Properties ausgenommen). */
        CORRECT,
        /** Anderer Block, aber gleiche Äquivalenzgruppe und Properties richtig (Feature 1). */
        SIMILAR,
        /** Soll-Block fehlt (Ist ist Luft oder nur Flüssigkeit). */
        MISSING,
        /** Falscher Blocktyp. */
        WRONG_TYPE,
        /** Typ ok, aber Properties (Richtung, half, waterlogged …) falsch. */
        WRONG_STATE,
        /** Block steht, wo die Schematic Luft vorsieht. */
        EXTRA;

        public boolean isError() { return this == MISSING || this == WRONG_TYPE || this == WRONG_STATE || this == EXTRA; }
        public boolean isOk() { return this == CORRECT || this == SIMILAR; }
    }

    /** Abweichende Property: Name, Soll-Wert, Ist-Wert ("—" wenn die Property beim Ist-Block fehlt). */
    public record PropDiff(String name, String expected, String actual) {}

    public boolean isError() { return status.isError(); }
}
