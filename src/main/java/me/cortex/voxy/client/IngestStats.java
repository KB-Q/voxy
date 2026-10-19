package me.cortex.voxy.client;

//Counts of the client side ingest paths (MixinRenderSectionManager), shown in the debug screen
// add/remove: whole chunk ingests when sodium starts/stops tracking a chunk, failed = no lighting yet, missing = chunk not present
// sectionBuilt: single section ingests when sodium builds a section mesh
public final class IngestStats {
    public static volatile int addOk, addFailed, addMissing;
    public static volatile int removeOk, removeFailed, removeMissing;
    public static volatile int sectionBuilt;
    //Server side ingests of chunks becoming full chunks on the integrated server (commonImpl MixinChunkMap)
    public static volatile int serverOk, serverFailed;
    //Chunks that became full before the voxy instance existed, ingested once it did (PendingServerIngest)
    public static volatile int serverDeferredOk;

    private IngestStats() {}

    public static String line() {
        return "Ingest chunk add ok/failed/missing " + addOk + "/" + addFailed + "/" + addMissing
                + ", remove " + removeOk + "/" + removeFailed + "/" + removeMissing + ", sections built " + sectionBuilt
                + ", server full chunk ok/failed " + serverOk + "/" + serverFailed + ", deferred " + serverDeferredOk;
    }
}
