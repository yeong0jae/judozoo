"""왜 오르나 스케줄 — 휴장일은 쉬고, 해외는 뉴욕 시간대로 깨어난다."""

from apscheduler.schedulers.background import BackgroundScheduler

from backend.insight import scheduler
from backend.market.calendar import Region


class Test깨어나는_때:
    def test_휴장일에는_만들지_않는다(self, mocker):
        mocker.patch.object(scheduler.calendar, "is_holiday", return_value=True)
        run = mocker.patch.object(scheduler.application, "run")

        scheduler.run_domestic()

        run.assert_not_called()

    def test_실패해도_삼키고_실패_카운터를_올린다(self, mocker):
        mocker.patch.object(scheduler.calendar, "is_holiday", return_value=False)
        mocker.patch.object(scheduler, "get_session_factory")
        mocker.patch.object(scheduler.application, "run", side_effect=RuntimeError("풀 조회 실패"))
        failed = mocker.patch.object(scheduler.metrics, "job_failed")

        scheduler.run_overseas()

        failed.assert_called_once_with("insight-reason-overseas")

    def test_해외는_뉴욕_시간대로_걸고_겹쳐_돌지_않는다(self):
        s = BackgroundScheduler()
        scheduler.register(s)

        job = s.get_job("insight-reason-overseas")
        assert str(job.trigger.timezone) == str(Region.US.zone)
        assert job.max_instances == 1
