from typing import Annotated

from fastapi import APIRouter, Request
from pydantic import BaseModel, Field

from .. import __version__
from . import PROBLEM_RESPONSES

router = APIRouter()


class EngineInfo(BaseModel):
    service: str
    apiVersion: str
    version: str
    replayStart: Annotated[
        str, Field(pattern=r"^[0-9]{4}-[0-9]{2}-01$", json_schema_extra={"format": "date"})
    ]


@router.get(
    "/v1/info",
    response_model=EngineInfo,
    operation_id="getEngineInfo",
    responses=PROBLEM_RESPONSES,
)
async def info(request: Request) -> EngineInfo:
    return EngineInfo(
        service="antlab-engine",
        apiVersion="v1",
        version=__version__,
        replayStart=request.app.state.replay_start.isoformat(),
    )
