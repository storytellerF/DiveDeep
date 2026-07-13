package dev.placeholder.llmd.ipc;

interface ILlmdService {
    String health();
    String listModels();
    String chatCompletion(String requestJson);
}
