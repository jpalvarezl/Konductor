package com.konductor.provider

import com.konductor.provider.inference.InferenceClient

class PromptProvider(inferenceClient: InferenceClient) : InferenceClient by inferenceClient
