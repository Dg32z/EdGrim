"""The endpoints of the plugin's contract, as a FastAPI application.

Shapes only: no storage, no real inference, no authentication. Those arrive in tickets #11-#16 without
changing anything the shared corpus pins.
"""

from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.model import ModelRegistry, Scorer, StubScorer, classify
from app.schemas import (
    CONTRACT_VERSION,
    ErrorResponse,
    InferenceRequest,
    InferenceResponse,
    ModelMetadata,
    SampleUploadRequest,
    SampleUploadResponse,
    Verdict,
)


def _no_model() -> JSONResponse:
    return JSONResponse(
        status_code=404,
        content=ErrorResponse(contract_version=CONTRACT_VERSION, error="no_model").model_dump(),
    )


def create_app(
    registry: ModelRegistry | None = None,
    scorer: Scorer | None = None,
) -> FastAPI:
    """Build the application.

    The registry and the scorer are injected so the cold-start path is a plain constructor argument
    rather than a global to poke at from a test.
    """
    active_registry = registry if registry is not None else ModelRegistry.with_default_model()
    active_scorer = scorer if scorer is not None else StubScorer()

    app = FastAPI(title="WatchNeko cloud", version=str(CONTRACT_VERSION))

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, exc: RequestValidationError) -> JSONResponse:
        # The contract says every non-2xx body carries the error envelope. FastAPI's default 422 body does
        # not, so it is translated here rather than documented as an exception to the rule.
        return JSONResponse(
            status_code=422,
            content=ErrorResponse(
                contract_version=CONTRACT_VERSION, error="invalid_request"
            ).model_dump(),
        )

    @app.get("/v1/model", response_model=ModelMetadata, responses={404: {"model": ErrorResponse}})
    def model_metadata():
        model = active_registry.current()
        if model is None:
            return _no_model()
        return ModelMetadata(
            contract_version=CONTRACT_VERSION,
            model_id=model.model_id,
            dataset_id=model.dataset_id,
            feature_dimensions=model.feature_dimensions,
            thresholds=model.thresholds,
        )

    @app.post(
        "/v1/inference",
        response_model=InferenceResponse,
        responses={404: {"model": ErrorResponse}},
    )
    def infer(payload: InferenceRequest):
        model = active_registry.current()
        if model is None:
            # The plugin never gets here: a cold start disables the check before it sends anything.
            return _no_model()

        verdicts = []
        for sample in payload.samples:
            score = active_scorer.score(sample.features)
            verdicts.append(
                Verdict(
                    sample_id=sample.sample_id,
                    score=score,
                    verdict=classify(score, model.thresholds),
                )
            )

        return InferenceResponse(
            contract_version=CONTRACT_VERSION,
            model_id=model.model_id,
            verdicts=verdicts,
        )

    @app.post("/v1/samples", response_model=SampleUploadResponse)
    def upload_samples(payload: SampleUploadRequest) -> SampleUploadResponse:
        # Storage is ticket #14. The contract phase owes a receipt that accounts for every sample sent.
        return SampleUploadResponse(
            contract_version=CONTRACT_VERSION,
            recording_session_id=payload.recording_session_id,
            accepted=len(payload.samples),
            rejected=0,
        )

    return app
