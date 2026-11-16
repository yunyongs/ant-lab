from fastapi import APIRouter
from pydantic import BaseModel

from .. import __version__
from . import PROBLEM_RESPONSES

router = APIRouter()


class EngineInfo(BaseModel):
    service: str
    apiVersion: str
    version: str


@router.get(
    "/v1/info",
    response_model=EngineInfo,
    operation_id="getEngineInfo",
    responses=PROBLEM_RESPONSES,
)
async def info() -> EngineInfo:
    return EngineInfo(service="antlab-engine", apiVersion="v1", version=__version__)
