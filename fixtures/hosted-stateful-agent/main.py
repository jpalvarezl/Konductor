"""Responses-protocol Hosted fixture for deterministic session lifecycle evidence."""

import asyncio
import logging

from azure.ai.agentserver.responses import (
    CreateResponse,
    ResponseContext,
    ResponsesAgentServerHost,
    TextResponse,
)

from marker_protocol import MarkerProtocol

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)
app = ResponsesAgentServerHost()
protocol = MarkerProtocol()


@app.create_handler
async def handler(
    request: CreateResponse,
    context: ResponseContext,
    cancellation_signal: asyncio.Event,
):
    input_text = await context.get_input_text()
    output_text = protocol.respond(input_text)
    logger.info("Handled marker protocol command: %s", input_text.partition(" ")[0])
    return TextResponse(context, request, text=output_text)


def main() -> None:
    app.run()


if __name__ == "__main__":
    main()
