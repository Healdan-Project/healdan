"""The Healdan extension for LibreOffice (spec 6): Open, Save and Save As go through Healdan Helper.

A thin layer over healdan_logic, which decides everything that doesn't need UNO and is tested on its own:

- a dispatch interceptor on every window, the Start Center's included, attached once (compared with UNO's ==)
  and handing Open, Save and Save As to healdan_logic.Flows, with LibreOffice's own command as the way back;
- a protocol handler for the Healdan menu and toolbar (Addons.xcu);
- a job on LibreOffice's events (Jobs.xcu), which attaches to windows as they appear, sets a copy's title as it
  opens, and when a copy closes tells the helper and takes it out of Recent Documents.

Threads. Nothing here waits on the network on LibreOffice's thread except under Ui.wait, which runs the call on a
thread of its own and keeps LibreOffice's events going meanwhile (XReschedule), as LibreOffice's own dialogs do:
the window routes, while the user is in the helper's window, and the steps of a filing. Ui.later runs a call in
the background, such as /v1/saved's wait of up to 120 s, and anything it then changes in the document goes back to
LibreOffice's thread through com.sun.star.awt.AsyncCallback.
"""

import contextlib
import logging
import logging.handlers
import os
import sys
import threading

import uno
import unohelper

from com.sun.star.awt import XCallback
from com.sun.star.frame import (XDispatch, XDispatchProvider, XDispatchProviderInterceptor, XDispatchResultListener,
                                XNotifyingDispatch)
from com.sun.star.lang import XEventListener, XInitialization
from com.sun.star.task import XJob

import healdan_logic as logic

JOB = "org.healdan.libreoffice.Job"
HANDLER = "org.healdan.libreoffice.ProtocolHandler"

# com.sun.star.frame.DispatchResultState, InfobarType; com.sun.star.beans.PropertyAttribute; ControlCharacter
SUCCESS, FAILURE, DONTKNOW = 1, 0, 2
INFOBAR_INFO, INFOBAR_WARNING = 0, 2
REMOVEABLE = 128
PARAGRAPH_BREAK = 0

INFOBAR = "org.healdan.libreoffice.notice"
SAVING = "Saving to Healdan\u2026"

_extension = None
_extension_lock = threading.Lock()


def extension(ctx):
    """The one Extension in this LibreOffice: its windows, flows and log."""
    global _extension
    with _extension_lock:
        if _extension is None:
            _extension = Extension(ctx)
        return _extension


def _start_log(support):
    log = logging.getLogger("healdan")
    if log.handlers:
        return log
    log.setLevel(logging.INFO)
    log.propagate = False
    path = logic.log_file(sys.platform, os.path.expanduser("~"), support)
    try:
        os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
        handler = logging.handlers.RotatingFileHandler(path, maxBytes=512 * 1024, backupCount=1, encoding="utf-8")
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
        log.addHandler(handler)
    except OSError:
        log.addHandler(logging.NullHandler())
    return log


def _named(values, name):
    for value in values or ():
        if getattr(value, "Name", None) == name:
            return value.Value
    return None


def _properties(**values):
    result = []
    for name, value in values.items():
        prop = uno.createUnoStruct("com.sun.star.beans.PropertyValue")
        prop.Name = name
        prop.Value = value
        result.append(prop)
    return tuple(result)


def _url_of(model):
    try:
        return model.getURL() if model is not None else ""
    except Exception:
        return ""


def _result(state):
    return {SUCCESS: True, FAILURE: False}.get(state)


def _state(result):
    return {True: SUCCESS, False: FAILURE}.get(result, DONTKNOW)


def _finished(listener, source, state):
    event = uno.createUnoStruct("com.sun.star.frame.DispatchResultEvent")
    event.Source = source
    event.State = state
    listener.dispatchFinished(event)


class _Collector(unohelper.Base, XDispatchResultListener):
    """What LibreOffice's own command answered."""

    def __init__(self):
        self.state = None

    def dispatchFinished(self, event):
        self.state = event.State

    def disposing(self, source):
        pass


def _run_own(dispatch, url, args):
    """Runs LibreOffice's own command: True when it did it, False when the user cancelled, None when it can't say."""
    if hasattr(dispatch, "dispatchWithNotification"):
        collector = _Collector()
        dispatch.dispatchWithNotification(url, args, collector)
        return _result(collector.state)
    dispatch.dispatch(url, args)
    return None


class _Callback(unohelper.Base, XCallback):
    def __init__(self, fn, log):
        self.fn = fn
        self.log = log

    def notify(self, data):
        try:
            self.fn()
        except Exception:
            self.log.exception("A step on LibreOffice's thread failed")


class Ui:
    """healdan_logic.Flows' ui (see there)."""

    def __init__(self, ext):
        self.ext = ext
        self._async = None

    def wait(self, fn):
        outcome = {}
        done = threading.Event()

        def run():
            try:
                outcome["value"] = fn()
            except BaseException as e:
                outcome["error"] = e
            finally:
                done.set()

        threading.Thread(target=run, name="healdan-call", daemon=True).start()
        toolkit = self.ext.create("com.sun.star.awt.Toolkit")
        while not done.wait(0.025):
            if toolkit is not None:
                try:
                    toolkit.reschedule()
                except Exception:
                    toolkit = None  # LibreOffice is going down: wait without it
        if "error" in outcome:
            raise outcome["error"]
        return outcome.get("value")

    def later(self, fn):
        def run():
            try:
                then = fn()
            except Exception:
                self.ext.log.exception("A background call failed")
                return
            if callable(then):
                self.on_main(then)

        threading.Thread(target=run, name="healdan-later", daemon=True).start()

    def on_main(self, fn):
        """Runs fn on LibreOffice's thread, once it's free."""
        if self._async is None:
            self._async = self.ext.create("com.sun.star.awt.AsyncCallback")
        self._async.addCallback(_Callback(fn, self.ext.log), None)

    def tell(self, doc, text, warning=True):
        """
        An infobar in the document's window, the first sentence bold and the rest regular (polish spec 8.3), or a
        message box where there's none (the Start Center).
        """
        headline, detail = logic.notice_parts(text)
        frame = None
        try:
            frame = doc.frame if doc is not None else self.ext.desktop().getCurrentFrame()
        except Exception:
            pass
        try:
            controller = frame.getController() if frame is not None else None
            if controller is not None and hasattr(controller, "appendInfobar"):
                if controller.hasInfobar(INFOBAR):
                    controller.removeInfobar(INFOBAR)
                controller.appendInfobar(INFOBAR, headline, detail, INFOBAR_WARNING if warning else INFOBAR_INFO,
                                         (), True)
                return
        except Exception:
            self.ext.log.exception("The infobar couldn't be shown")
        try:
            toolkit = self.ext.create("com.sun.star.awt.Toolkit")
            kind = uno.Enum("com.sun.star.awt.MessageBoxType", "WARNINGBOX" if warning else "INFOBOX")
            parent = frame.getContainerWindow() if frame is not None else None
            toolkit.createMessageBox(parent, kind, 1, "Healdan",
                                     headline + ("\n\n" + detail if detail else "")).execute()  # 1: BUTTONS_OK
        except Exception:
            self.ext.log.exception("The notice couldn't be shown: %s", text)

    def withdraw(self, doc):
        """Takes the extension's infobar out of the document's window, if it shows one."""
        try:
            controller = doc.frame.getController()
            if controller is not None and hasattr(controller, "hasInfobar") and controller.hasInfobar(INFOBAR):
                controller.removeInfobar(INFOBAR)
        except Exception:
            self.ext.log.exception("The infobar couldn't be removed")


class Doc:
    """healdan_logic.Flows' doc (see there): a LibreOffice document, through its model."""

    def __init__(self, ext, model, frame=None):
        self.ext = ext
        self.model = model
        self._frame = frame

    @property
    def frame(self):
        if self._frame is not None:
            return self._frame
        return self.model.getCurrentController().getFrame()

    @property
    def url(self):
        return _url_of(self.model)

    @property
    def kind(self):
        try:
            return logic.kind_of(self.ext.create("com.sun.star.frame.ModuleManager").identify(self.model))
        except Exception:
            return None

    @property
    def title(self):
        try:
            return self.model.getTitle()
        except Exception:
            return ""

    @property
    def read_only(self):
        try:
            if self.model.isReadonly():
                return True
        except Exception:
            pass
        path = logic.url_to_path(self.url)
        return bool(path) and os.path.exists(path) and not os.access(path, os.W_OK)

    @property
    def modified(self):
        try:
            return bool(self.model.isModified())
        except Exception:
            return False

    @property
    def alive(self):
        try:
            self.model.getURL()
            return True
        except Exception:
            return False

    def store(self):
        # XStorable.store keeps the file's own format and asks nothing, unlike LibreOffice's Save (spec 6.2).
        self.model.store()

    def write_to(self, path, filter_name):
        self.model.storeToURL(uno.systemPathToFileUrl(path), _properties(FilterName=filter_name, Overwrite=True))

    def adopt(self, path, filter_name):
        self.model.storeAsURL(uno.systemPathToFileUrl(path), _properties(FilterName=filter_name))

    def set_title(self, title):
        if title:
            self.model.setTitle(title)

    def _user_properties(self):
        return self.model.getDocumentProperties().getUserDefinedProperties()

    def set_doc_id(self, value):
        props = self._user_properties()
        if not props.getPropertySetInfo().hasPropertyByName(logic.DOC_ID_PROPERTY):
            return False
        was_modified = self.model.isModified()
        if props.getPropertyValue(logic.DOC_ID_PROPERTY) != value:
            props.setPropertyValue(logic.DOC_ID_PROPERTY, value)
        self._refresh_fields()
        if not was_modified and self.model.isModified():
            self.model.setModified(False)  # only the change it made, which the next save carries (spec 5.9)
        return True

    def _refresh_fields(self):
        if hasattr(self.model, "getTextFields"):
            self.model.getTextFields().refresh()

    def insert_doc_id(self, value):
        """The property, and a field showing it in the current page style's footer, turned on if need be (spec 5.9)."""
        props = self._user_properties()
        if props.getPropertySetInfo().hasPropertyByName(logic.DOC_ID_PROPERTY):
            if value is not None:
                props.setPropertyValue(logic.DOC_ID_PROPERTY, value)
        else:
            props.addProperty(logic.DOC_ID_PROPERTY, REMOVEABLE, value or "")
        cursor = self.model.getCurrentController().getViewCursor()
        style = self.model.getStyleFamilies().getByName("PageStyles").getByName(cursor.PageStyleName)
        if not style.FooterIsOn:
            style.FooterIsOn = True
        for text in self._footers(style):
            if not self._shows_doc_id(text):
                self._append_field(text)
        self._refresh_fields()

    @staticmethod
    def _footers(style):
        texts = [style.FooterText]
        try:
            if not style.FooterIsShared:
                texts.append(style.FooterTextLeft)
        except Exception:
            pass
        try:
            if not style.FirstIsShared:
                texts.append(style.FooterTextFirst)
        except Exception:
            pass
        unique = []
        for text in texts:
            if text is not None and not any(text == seen for seen in unique):
                unique.append(text)
        return unique

    @staticmethod
    def _shows_doc_id(text):
        paragraphs = text.createEnumeration()
        while paragraphs.hasMoreElements():
            paragraph = paragraphs.nextElement()
            if not paragraph.supportsService("com.sun.star.text.Paragraph"):
                continue  # a table
            portions = paragraph.createEnumeration()
            while portions.hasMoreElements():
                portion = portions.nextElement()
                if portion.TextPortionType != "TextField":
                    continue
                field = portion.TextField
                if (field.supportsService("com.sun.star.text.textfield.docinfo.Custom")
                        and field.Name == logic.DOC_ID_PROPERTY):
                    return True
        return False

    def _append_field(self, text):
        if text.getString():
            text.insertControlCharacter(text.getEnd(), PARAGRAPH_BREAK, False)
        field = self.model.createInstance("com.sun.star.text.textfield.docinfo.Custom")
        field.Name = logic.DOC_ID_PROPERTY
        text.insertTextContent(text.getEnd(), field, False)

    @contextlib.contextmanager
    def locked(self):
        """No typing into the window while it's held, and the status bar says why."""
        window = indicator = None
        try:
            window = self.frame.getContainerWindow()
            window.setEnable(False)
        except Exception:
            window = None
        try:
            indicator = self.frame.createStatusIndicator()
            indicator.start(SAVING, 0)
        except Exception:
            indicator = None
        try:
            yield
        finally:
            if indicator is not None:
                try:
                    indicator.end()
                except Exception:
                    pass
            if window is not None:
                try:
                    window.setEnable(True)
                except Exception:
                    pass


class Extension:
    """The extension in this LibreOffice: the windows it's attached to, the flows, and LibreOffice's events."""

    def __init__(self, ctx):
        self.ctx = ctx
        support = logic.support_dir()
        self.copies = logic.copies_dir(support)
        self.log = _start_log(support)
        self.ui = Ui(self)
        self.flows = logic.Flows(logic.Channel(support), self.copies, self.ui, self.log)
        self.frames = []  # (frame, interceptor) for each window, the Start Center's included
        self._stop = threading.Event()
        self._scanner = None
        self._scan_waiting = False
        self.log.info("The Healdan extension started in LibreOffice")

    def create(self, name):
        return self.ctx.ServiceManager.createInstanceWithContext(name, self.ctx)

    def desktop(self):
        return self.create("com.sun.star.frame.Desktop")

    # -- events (Jobs.xcu)

    def event(self, name, model, frame):
        if name == "OnCloseApp":
            self._stop.set()
            return
        self._start_scanning()
        if name in ("OnStartApp", "onFirstVisibleTask"):
            if frame is not None:
                self.attach(frame)
            self.attach_all()
            if name == "OnStartApp":
                self.forget_recent()  # anything left from a crash
        elif name in ("OnNew", "OnLoad", "OnViewCreated"):
            for each in self._frames_of(model, frame):
                self.attach(each)
            if name == "OnLoad" and model is not None:
                self.flows.loaded(Doc(self, model))
        elif name == "OnUnload":
            self._unloaded(model)

    def _unloaded(self, model):
        url = _url_of(model)
        self.flows.window_closed(model)
        if logic.is_copy(url, self.copies):
            # A moment later: File > Reload unloads the old document after loading the new one, which isn't among the
            # desktop's documents yet. The helper checks a copy in and deletes it when told it closed, so a copy still
            # open, or still locked, isn't reported: the helper sees its lock file go instead.
            self.ui.on_main(lambda: self.flows.closed(url, self._still_open(model, url)))
        # The copy's entry was added as it closed, before this event: it goes now, on LibreOffice's thread (spec 6.2).
        self.forget_recent()

    def _still_open(self, model, url):
        path = logic.url_to_path(url)
        if path and os.path.exists(logic.lock_file(path)):
            return True
        components = self.desktop().getComponents().createEnumeration()
        while components.hasMoreElements():
            component = components.nextElement()
            try:
                if not (component == model) and hasattr(component, "getURL") and component.getURL() == url:
                    return True
            except Exception:
                continue
        return False

    # -- attaching once to every window

    @staticmethod
    def _frames_of(model, frame):
        frames = [frame] if frame is not None else []
        try:
            controllers = model.getControllers()
            while controllers.hasMoreElements():
                frames.append(controllers.nextElement().getFrame())
        except Exception:
            try:
                frames.append(model.getCurrentController().getFrame())
            except Exception:
                pass
        return [each for each in frames if each is not None]

    def attach(self, frame):
        """One interceptor per window, compared with UNO's ==: two made the trial's window open twice."""
        if frame is None:
            return
        for known, _ in self.frames:
            try:
                if known == frame:
                    return
            except Exception:
                continue
        interceptor = Interceptor(self, frame)
        frame.registerDispatchProviderInterceptor(interceptor)
        frame.addEventListener(_FrameGone(self, frame))
        self.frames.append((frame, interceptor))

    def forget_frame(self, frame):
        kept = []
        for known, interceptor in self.frames:
            try:
                if known == frame:
                    continue
            except Exception:
                continue
            kept.append((known, interceptor))
        self.frames = kept

    def interceptor_of(self, frame):
        for known, interceptor in self.frames:
            try:
                if known == frame:
                    return interceptor
            except Exception:
                continue
        return None

    def attach_all(self):
        self._scan_waiting = False
        frames = self.desktop().getFrames()
        for i in range(frames.getCount()):
            try:
                self.attach(frames.getByIndex(i))
            except Exception:
                self.log.exception("Couldn't attach to a window")

    def _start_scanning(self):
        """
        Every second, any window not yet attached: LibreOffice sends no event for a Start Center it makes, as on
        macOS when the Dock icon is clicked with no window open (spec 3.1).
        """
        if self._scanner is not None:
            return

        def scan():
            while not self._stop.wait(1.0):
                if not self._scan_waiting:
                    self._scan_waiting = True
                    try:
                        self.ui.on_main(self.attach_all)
                    except Exception:
                        self._scan_waiting = False

        self._scanner = threading.Thread(target=scan, name="healdan-windows", daemon=True)
        self._scanner.start()

    # -- commands

    def doc_for(self, frame):
        try:
            controller = frame.getController()
            model = controller.getModel() if controller is not None else None
        except Exception:
            return None
        if model is None or not hasattr(model, "getURL"):
            return None  # the Start Center
        return Doc(self, model, frame)

    def own_command(self, frame, command, args=()):
        """LibreOffice's own command in this window, past the interceptor: the way back to its own boxes."""
        transformer = self.create("com.sun.star.util.URLTransformer")
        url = uno.createUnoStruct("com.sun.star.util.URL")
        url.Complete = command
        parsed = transformer.parseStrict(url)
        if isinstance(parsed, tuple):
            url = parsed[-1]
        interceptor = self.interceptor_of(frame)
        provider = interceptor.slave if interceptor is not None and interceptor.slave is not None else frame
        dispatch = provider.queryDispatch(url, "", 0)
        if dispatch is None:
            return False
        return _run_own(dispatch, url, args)

    def command(self, command, frame, original):
        return self.flows.command(command, self.doc_for(frame), original)

    def button(self, name, frame):
        self.log.info("Healdan command %s", name)
        return self.flows.button(name, self.doc_for(frame), lambda: self.own_command(frame, logic.OPENS[0]),
                                 lambda: self.own_command(frame, logic.SAVE_AS))

    def failed(self, frame, command, error):
        """What escapes the flows is LibreOffice's own command failing: logged, and told briefly."""
        self.log.error("%s failed: %s", command, logic.said(error))
        self.flows.tell(self.doc_for(frame), logic.FAILED_AFTER.format(error=logic.said(error)))

    # -- Recent Documents

    def forget_recent(self):
        """Takes Healdan's copies out of LibreOffice's Recent Documents (the PickList), as its own DeleteItem does."""
        try:
            provider = self.create("com.sun.star.configuration.ConfigurationProvider")
            access = provider.createInstanceWithArguments(
                "com.sun.star.configuration.ConfigurationUpdateAccess",
                _properties(nodepath="/org.openoffice.Office.Histories/Histories"))
            if not access.hasByName("PickList"):
                return
            picks = access.getByName("PickList")
            items, order = picks.getByName("ItemList"), picks.getByName("OrderList")
            entries = [(name, order.getByName(name).getPropertyValue("HistoryItemRef"))
                       for name in order.getElementNames()]
            forget = logic.recent_entries_to_forget(list(items.getElementNames()) + [url for _, url in entries],
                                                    self.copies)
            if not forget:
                return
            refs, removed = logic.pick_list_after(entries, forget)
            for name in removed:
                order.removeByName(name)
            for name, url in refs.items():
                if order.hasByName(name):
                    order.getByName(name).setPropertyValue("HistoryItemRef", url)
                else:
                    entry = order.createInstance()
                    entry.setPropertyValue("HistoryItemRef", url)
                    order.insertByName(name, entry)
            for url in forget:
                if items.hasByName(url):
                    items.removeByName(url)
            access.commitChanges()
            self.log.info("Took %d copies out of Recent Documents", len(forget))
        except Exception:
            self.log.exception("Couldn't tidy Recent Documents")


class _FrameGone(unohelper.Base, XEventListener):
    def __init__(self, ext, frame):
        self.ext = ext
        self.frame = frame

    def disposing(self, source):
        self.ext.forget_frame(self.frame)


class Interceptor(unohelper.Base, XDispatchProviderInterceptor):
    """Takes Open, Save and Save As over in one window; every other command goes on to LibreOffice."""

    def __init__(self, ext, frame):
        self.ext = ext
        self.frame = frame
        self.slave = None
        self.master = None

    def getSlaveDispatchProvider(self):
        return self.slave

    def setSlaveDispatchProvider(self, provider):
        self.slave = provider

    def getMasterDispatchProvider(self):
        return self.master

    def setMasterDispatchProvider(self, provider):
        self.master = provider

    def queryDispatch(self, url, target, flags):
        own = self.slave.queryDispatch(url, target, flags) if self.slave is not None else None
        if own is not None and logic.intercepted(url.Complete):
            return Dispatch(self.ext, self.frame, own)
        return own

    def queryDispatches(self, requests):
        return tuple(self.queryDispatch(r.FeatureURL, r.FrameName, r.SearchFlags) for r in requests)


class Dispatch(unohelper.Base, XNotifyingDispatch):
    """
    One intercepted command. Its state (enabled, the Save button's modified mark) is LibreOffice's own. A caller
    waiting for the result, such as LibreOffice's "Save changes?" on close, hears it before dispatch returns.
    """

    def __init__(self, ext, frame, own):
        self.ext = ext
        self.frame = frame
        self.own = own

    def dispatch(self, url, args):
        self._run(url, args, None)

    def dispatchWithNotification(self, url, args, listener):
        self._run(url, args, listener)

    def _run(self, url, args, listener):
        state = DONTKNOW
        try:
            result = self.ext.command(url.Complete, self.frame, lambda: _run_own(self.own, url, args))
            state = _state(result)
        except Exception as e:
            state = FAILURE
            try:
                self.ext.failed(self.frame, url.Complete, e)
            except Exception:
                pass
        finally:
            if listener is not None:
                try:
                    _finished(listener, self, state)
                except Exception:
                    self.ext.log.exception("The result of %s couldn't be given", url.Complete)

    def addStatusListener(self, listener, url):
        self.own.addStatusListener(listener, url)

    def removeStatusListener(self, listener, url):
        self.own.removeStatusListener(listener, url)


class Handler(unohelper.Base, XDispatchProvider, XDispatch, XInitialization):
    """The Healdan menu's and toolbar's commands (org.healdan.libreoffice:*), for the window they're in."""

    def __init__(self, ctx):
        self.ctx = ctx
        self.frame = None

    def initialize(self, args):
        if args:
            self.frame = args[0]

    def queryDispatch(self, url, target, flags):
        return self if logic.button(url.Complete) else None

    def queryDispatches(self, requests):
        return tuple(self.queryDispatch(r.FeatureURL, r.FrameName, r.SearchFlags) for r in requests)

    def dispatch(self, url, args):
        try:
            ext = extension(self.ctx)
            ext.attach(self.frame)
            try:
                ext.button(logic.button(url.Complete), self.frame)
            except Exception as e:
                ext.failed(self.frame, url.Complete, e)
        except Exception:
            logging.getLogger("healdan").exception("%s failed", url.Complete)

    def addStatusListener(self, listener, url):
        event = uno.createUnoStruct("com.sun.star.frame.FeatureStateEvent")
        event.FeatureURL = url
        event.IsEnabled = True
        event.Requery = False
        event.Source = self
        listener.statusChanged(event)

    def removeStatusListener(self, listener, url):
        pass


class Job(unohelper.Base, XJob):
    """LibreOffice's events, from Jobs.xcu."""

    def __init__(self, ctx):
        self.ctx = ctx

    def execute(self, args):
        try:
            environment = _named(args, "Environment")
            extension(self.ctx).event(_named(environment, "EventName"), _named(environment, "Model"),
                                      _named(environment, "Frame"))
        except Exception:
            logging.getLogger("healdan").exception("An event failed")
        return None


g_ImplementationHelper = unohelper.ImplementationHelper()
g_ImplementationHelper.addImplementation(Job, JOB, (JOB, "com.sun.star.task.Job"))
g_ImplementationHelper.addImplementation(Handler, HANDLER, (HANDLER, "com.sun.star.frame.ProtocolHandler"))
