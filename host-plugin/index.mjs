/**
 * LittleWhale's dsh tools
 *
 * These run in the host process, which is the app's own Node child and shares its uid, so the
 * privileged channel is one loopback connection away: the app process serves it and holds the
 * binder to the root or shell process, which is something Node cannot do for itself
 *
 * Both the endpoint and the token arrive in environment variables the app sets when it spawns the
 * host, so the same tools on a machine that is not a phone report exactly that instead of failing
 * obscurely
 *
 * Every call that changes a screen names the screen it means. Which screen the app's preview shows
 * is the user's to change at any moment, so a tool that named nothing could act on the one the user
 * had just switched to - the coordinates a caller holds are only meaningful against the screen it
 * measured them on
 */

import { connect } from 'node:net'

import { defineTool } from '@deepseek-ai/dsh-tools'

/** Set by the app when it starts the host; both absent everywhere else */
const ENDPOINT_VARIABLE = 'LW_CHANNEL_ENDPOINT'
const TOKEN_VARIABLE = 'LW_CHANNEL_TOKEN'

/** How long a swipe takes when the caller does not say, which is a deliberate drag */
const DEFAULT_SWIPE_MS = 300

/** 按名字点一个从像素里读出来的框时, 落点从框中间这一块里随机取, 见 scatterInside */
const SCATTER_INSET = 0.2

/**
 * What the three names for a hold mean, in milliseconds
 *
 * Android's own long press threshold is 500ms (`ViewConfiguration.getLongPressTimeout`), and that
 * is the whole of what the platform reads into a hold: everything at or past it is a long press and
 * everything past that is only "kept down longer", which matters to the few apps that count - a
 * voice message being recorded, a drag that starts by holding. So the names sit just above that
 * line rather than in the seconds: `short` is the shortest hold that is reliably a long press
 * (600ms, which leaves the main thread's own detector its margin), and the other two are the steps
 * above it. A hold nobody named - "8s", "250ms" - is written out instead, which is also how a
 * deliberate long one is asked for
 */
const HOLD_NAMES = { short: 600, medium: 1500, long: 3000 }

/** No hold lasts longer than this: the device's gesture queue is blocked for the whole of one */
const MAX_HOLD_MS = 10000

/** How many controls one lw_ui lists before it says the rest are off screen */
const MAX_UI_NODES = 200

export const name = 'littlewhale-channel'

/** The tool registry has to exist before anything can be registered on it */
export const inject = ['tools']

export function apply(ctx) {
  for (const tool of TOOLS) ctx.tools.register(tool)

  // Answer every approval request with a grant, so nothing on the screen waits for a person
  //
  // An answerer is not registered on a service: it is a listener on the `approval/request`
  // waterfall, and returning an outcome answers for every agent while `next()` would delegate.
  // `prepend` is what makes it decisive - this plugin is mounted from the last patch layer, so
  // without it the web GUI's own approval panel would claim a request first whenever a browser
  // has the GUI open, and the user would be asked to confirm something they cannot see
  //
  // This is a deliberate trade, recorded in AGENTS.md: a screen being driven at a distance has
  // nobody watching it, so a question is not a safety net, it is a stall. The approval log still
  // records that a decision was made and what it was
  ctx.on('approval/request', (_request, _next) => Promise.resolve('allowed-once'), { prepend: true })
}

/** The display a call is about, which is the id `lw_screen` reports and nothing else */
const DISPLAY_ID = {
  type: 'integer',
  required: true,
  description:
    'The displayId of the screen to act on, exactly as lw_screen reports it. Display 0 is the '
    + "phone's own screen, the glass the person holding it is looking at; every other id is a "
    + 'virtual screen this host made. Prefer a virtual screen whenever the user has not named one: '
    + 'display 0 is the phone in somebody\'s hand, so anything aimed at it takes the phone away '
    + 'from them, and a call is refused outright while their finger is on the glass while a drag '
    + 'on it is cut short. Make one with lw_screen_create instead of reaching for the id that is '
    + 'already there. When a call comes back saying the screen is gone, is paused, or is in the '
    + 'user\'s hands, read the reason it gives: the user did it from the phone, or an agent closed '
    + 'it with lw_screen_release. Either way stop, say which screen it is, and ask the user what '
    + 'they want - do not retry the id, and do not move on to another screen on your own.',
}

/**
 * What one acting call is for, said by the caller in its own words
 *
 * Every call that changes something on a screen carries one, the way an agent framework asks for a
 * line of explanation beside a command. It is required rather than optional on purpose: saying what
 * a step is for before taking it is the difference between a transcript a person can follow and a
 * list of coordinates, and there is no human in the approval path to ask instead
 */
const NOTE = {
  type: 'string',
  required: true,
  description: 'One short sentence, in your own words, saying what this step is doing and why',
}

const TOOLS = [
  defineTool({
    name: 'lw_probe',
    description:
      'Probe the LittleWhale privileged channel on the Android device this host runs on. '
      + 'Reports the uid the privileged helper process runs as (0 means root), its pid, and the '
      + 'touchscreen ranges that getevent -p reports for it. Use this to check that the channel '
      + 'is up before anything relies on controlling the screen.',
    parameters: {},
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute() {
      return formatProbe(await call('probe'))
    },
  }),

  defineTool({
    name: 'lw_screen',
    description:
      'List the screens this device can be asked to act on: the phone\'s own screen, and every '
      + 'virtual screen the host is running. A virtual screen is a separate Android display with '
      + 'its own size, its own apps and its own picture, and it keeps running whether or not the '
      + 'phone is showing it. Call this before any other lw_ tool that acts on a screen: the '
      + 'displayId it reports is what those calls name, and the size it reports is the coordinate '
      + 'space their points are in. Display 0 is the phone\'s own screen, the one a person is '
      + 'holding - it is always there, nothing can be created or released there, and whether it can '
      + 'be driven right now is in the same answer, because a real finger on the glass stops '
      + 'everything aimed at it. Unless the user asked for that screen in so many words, work on a '
      + 'virtual one instead: it is theirs to keep using, and only a screen of ours can be given '
      + 'the shape an app wants (lw_screen_create, then lw_screen_resize or lw_screen_rotate). Two '
      + 'things here are not yours to undo: a screen you were working '
      + 'on may be missing, and a screen may say its control is paused. A paused screen says so in '
      + 'this list; a missing one does not, but a call naming its id answers with the reason - the '
      + 'user closed it from the phone, or an agent closed it with lw_screen_release, which may '
      + 'have been another session, since screens are shared. In every one of those cases stop, say '
      + 'which screen it is, and ask the user what they want instead of retrying or carrying on '
      + 'with a different screen.',
    parameters: {},
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute() {
      return formatScreens(await call('screen'))
    },
  }),

  defineTool({
    name: 'lw_screen_create',
    description:
      'Create a virtual screen on the Android device and answer with its displayId. This is the '
      + 'usual way to work: a screen of its own leaves the phone in the user\'s hands, where '
      + 'acting on display 0 would take it away from them. The screen '
      + 'starts empty: nothing is drawn on it until an app is launched onto it with lw_launch, and '
      + 'the apps you launch stay on the display you launched them on. Give it a name to tell your '
      + 'screens apart - a name that is taken gets a number appended. Width, height and dpi default '
      + "to the device's own screen, so leave them out to get a screen the size of the phone, and "
      + 'give them when you already know the shape the app wants - a game or any other '
      + 'landscape-only app wants a width larger than its height, and an app that declares an '
      + 'orientation of its own is left in a band in the middle of a screen shaped the other way '
      + 'rather than laid out to fill it. It is '
      + 'not final either way, since lw_screen_resize and lw_screen_rotate change a screen that '
      + "already exists with its apps still running. The phone's own screen is not created here: "
      + 'it is displayId 0 and it is always there.',
    parameters: {
      name: {
        type: 'string',
        description: 'What to call this screen, for example the app you mean to put on it',
      },
      width: {
        type: 'integer',
        description: 'Width in pixels; the device\'s own screen when omitted. Larger than height'
          + ' for a landscape app, smaller for a portrait one',
      },
      height: {
        type: 'integer',
        description: 'Height in pixels; the device\'s own screen when omitted',
      },
      dpi: {
        type: 'integer',
        description: 'Pixel density; the device\'s own density when omitted',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatCreated(await call('create', drop(args)))
    },
  }),

  defineTool({
    name: 'lw_screen_resize',
    description:
      'Give an existing virtual screen another size, with whatever is running on it left running. '
      + 'A display\'s size is what an app lays itself out for, so this is how a screen made '
      + 'portrait becomes the landscape one a game wants: the picture cannot be rotated into it. '
      + 'Name the width, the height, the dpi, or as many of those as you mean to change - every '
      + 'one you leave out keeps the value the screen has. The apps on the screen are not '
      + 'restarted: the new size reaches them as a configuration change, so one that follows its '
      + 'screen lays itself out again and fills it, while one that declares an orientation of its '
      + 'own - most system apps are portrait-only - keeps that layout and sits in a band in the '
      + 'middle of the screen instead. Take a fresh picture before '
      + 'reading coordinates off this screen, since every one of them has moved. The phone\'s own '
      + 'screen (displayId 0) has no size of ours to change and is refused.',
    parameters: {
      displayId: DISPLAY_ID,
      width: {
        type: 'integer',
        description: 'The screen\'s new width in pixels; it keeps its own when omitted',
      },
      height: {
        type: 'integer',
        description: 'The screen\'s new height in pixels; it keeps its own when omitted',
      },
      dpi: {
        type: 'integer',
        description: 'The screen\'s new pixel density; it keeps its own when omitted',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const width = Number(args?.width ?? 0)
      const height = Number(args?.height ?? 0)
      const dpi = Number(args?.dpi ?? 0)
      if (!(width > 0 || height > 0 || dpi > 0)) {
        throw new Error('lw_screen_resize needs at least one of width, height or dpi to change')
      }
      return formatResized(await call('resize', drop(args)))
    },
  }),

  defineTool({
    name: 'lw_screen_rotate',
    description:
      'Turn a virtual screen a quarter turn: exactly lw_screen_resize with the width and the '
      + 'height traded for each other. A screen has a shape rather than an orientation of its '
      + 'own, so the two directions are the same turn and there is no clockwise to name. Reach for '
      + 'this when an app turns out to want the other shape - and take a fresh picture afterwards, '
      + 'because what is on the screen is laid out again at the new size rather than rotated, so '
      + 'all of its coordinates move.',
    parameters: {
      displayId: DISPLAY_ID,
      dpi: {
        type: 'integer',
        description: 'The screen\'s new pixel density; it keeps its own when omitted',
      },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatResized(await call('resize', { ...drop(args), swap: true }))
    },
  }),

  defineTool({
    name: 'lw_screen_release',
    description:
      'Give one virtual screen back, which takes everything running on it down with it. '
      + 'Irreversible: any app you launched onto that display has to be launched again on a new '
      + 'one. Name the displayId to release - there is no default, because releasing the wrong '
      + 'screen destroys work nobody asked to lose, and displayId 0 is the phone\'s own screen, '
      + 'which cannot be released at all. Screens are shared with every other session on '
      + 'this host, so a screen you did not create for this task is not yours to close: ask the '
      + 'user first. Whoever calls this is recorded as the closer, so a later call on that id is '
      + 'told an agent closed it rather than the user.',
    parameters: { displayId: DISPLAY_ID, note: NOTE },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatReleased(await call('release', drop(args, 'note')), args?.note)
    },
  }),

  defineTool({
    name: 'lw_ui',
    description:
      'Read what one screen says: every control on it that has text, a description or an '
      + 'action, each with the rectangle it occupies in the screen\'s own pixels. Works on a '
      + 'virtual screen and on the phone\'s own screen (displayId 0) alike, including what the '
      + 'person holding the phone is looking at. Use this instead '
      + 'of lw_screenshot whenever it answers: the text is exact and the coordinates are exact, '
      + 'while coordinates measured on a picture have been scaled and rounded on the way. Press '
      + 'what it lists with lw_tap(text="..."), which needs no coordinate at all. An app that '
      + 'draws its own interface - a game, Flutter, a canvas or a WebGL view - has no such tree, '
      + 'and this answers that it found nothing: fall back to lw_ocr there, which reads the text '
      + 'off the pixels.',
    parameters: { displayId: DISPLAY_ID },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatUi(await call('ui', drop(args)))
    },
  }),

  defineTool({
    name: 'lw_ocr',
    description:
      'Read the text on a screen from its pixels. This is the fallback for a screen whose controls '
      + 'cannot be named: an app that draws its own interface - a game, a Unity or Cocos canvas, a '
      + 'WebGL view - has no control tree, but its text is still on the screen. Every line comes '
      + 'back with the rectangle it occupies in the screen\'s own pixels, so lw_tap(x=..., y=...) '
      + 'can press it; the centre is given too. It reads Chinese and English, runs on the device '
      + 'with no network and no cloud, and unlike lw_screenshot it does **not** scale the picture '
      + 'down, so small text stays readable. Prefer lw_ui whenever that answers: it is exact and '
      + 'free, while this is a reading of a picture, and it can mistake an icon for a character '
      + '(those come back with a low score).',
    parameters: { displayId: DISPLAY_ID, note: NOTE },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return said(formatOcr(await call('ocr', drop(args, 'note'))), args?.note)
    },
  }),

  defineTool({
    name: 'lw_tap',
    description:
      'Press something on a screen, either by name or at a point - on a virtual screen, or on the '
      + "phone's own screen with displayId 0. By name is the reliable "
      + 'way: pass text exactly as lw_ui reported it and the device finds that control and presses '
      + 'it, reporting what it pressed. If the name reaches more than one control nothing is '
      + 'pressed and the candidates come back, so the next attempt can be more specific. A point '
      + 'is for when there is no name to use - a canvas, or a place you measured on a screenshot - '
      + 'and it is in the screen\'s own pixels, with 0,0 at its top left. Either way the call '
      + 'returns once the device has delivered the press, so a screenshot or an lw_ui taken after '
      + 'it shows the result. Pass hold to make it a long press, which is how a context menu, a '
      + 'selection or a drag handle is reached; by name that uses the control\'s own long click '
      + 'action, and a control without one is pressed with a held finger instead. On the phone\'s '
      + 'own screen the user has the last word: if their '
      + 'finger is on the glass the press is not delivered and the answer says so - stop, say what '
      + 'you were doing, and ask them, because retrying it will be refused too. A press that is '
      + 'held can also be taken away in the middle: the answer then says how long it lasted, and '
      + 'what followed is not the result of a press that finished.',
    parameters: {
      displayId: DISPLAY_ID,
      text: {
        type: 'string',
        description: 'Name of the control to press: its text if it has one, otherwise the desc= '
          + 'value lw_ui reported for it',
      },
      x: { type: 'number', description: 'Horizontal position of the point, instead of a text' },
      y: { type: 'number', description: 'Vertical position of the point, instead of a text' },
      source: {
        type: 'string',
        enum: ['auto', 'a11y', 'ocr'],
        description: 'Where to look the name up. auto (the default) asks the control tree first '
          + 'and, only if that tree has no such name, reads the screen\'s pixels with lw_ocr - '
          + 'which is what a self-drawn screen needs. a11y is the tree alone, ocr is the pixels '
          + 'alone. An ambiguous name is never followed by a guess, whichever way it was found',
      },
      hold: holdParameter(
        'A hold past a long press is a press kept down, which is what a voice message being '
        + 'recorded or a drag that has to be started by holding expects.',
      ),
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const named = args?.text !== undefined
      const placed = args?.x !== undefined && args?.y !== undefined
      if (named && placed) {
        throw new Error('lw_tap takes either a text or a point, not both')
      }
      if (!named && !placed) {
        throw new Error('lw_tap needs either a text to press by name or both x and y')
      }
      const held = holdMs(args?.hold)
      if (named) {
        const source = args.source ?? 'auto'
        const asked = { displayId: args.displayId, text: args.text, holdMs: held }
        if (source === 'ocr') {
          return said(await tapByReading(args.displayId, args.text, held), args.note)
        }
        const tree = await call('tapText', asked)
        // ambiguous 也直接交回去: 树里说有两个同名控件的时候, 读像素只会是第三个猜测
        // 这里看的是 clicked 而不是 outcome: 无障碍动作被拒之后桥会用一根按住的手指兜底,
        // 那一下 outcome 仍是 unclicked 而 clicked 已经是真, 再退到读屏就会按第二次
        if (source === 'a11y' || tree.clicked === true || tree.outcome === 'ambiguous') {
          return formatNamedTap(tree, args.note)
        }
        const reading = await tapByReading(args.displayId, args.text, held)
        return said(`${namedTapReport(tree)}\n${reading}`, args.note)
      }
      return formatGesture('tapped', await call('tap', {
        displayId: args.displayId,
        x: args.x,
        y: args.y,
        holdMs: held,
      }), args.note)
    },
  }),

  defineTool({
    name: 'lw_key',
    description:
      'Press a key on a screen: BACK, HOME, APP_SWITCH, ENTER, DEL, the volume keys, the arrows, and '
      + 'anything else Android has a keycode for. This is how a button the platform itself handles '
      + 'is pressed - BACK, HOME and the volume keys are on no screen\'s tree, so lw_ui and lw_tap '
      + 'cannot reach them - and it is the only way: the input command is refused for this app\'s '
      + 'uid, exactly like am, so a shell cannot do it either. Name the key the way Android names '
      + 'it, with or without the KEYCODE_ prefix (BACK, keycode_home, APP_SWITCH, DPAD_DOWN, '
      + 'MOVE_END), or pass its number as code. A name Android does not have, or a number it does '
      + 'not define, is refused and the answer says what to use instead - so do not guess a number, '
      + 'the wrong one is a different key rather than an error. Pass hold to keep the key down: a '
      + 'long BACK force-stops the app in front, a long HOME calls the assistant, a long press on a '
      + 'power key opens its menu. Works on a virtual screen and on the '
      + 'phone\'s own screen (displayId 0), where the user\'s own finger stops it like every other '
      + 'acting call. HOME and the power and sleep keys are refused on a virtual screen: a screen '
      + 'of this host has no launcher, so they belong on displayId 0.',
    parameters: {
      displayId: DISPLAY_ID,
      key: {
        type: 'string',
        description: 'The Android name of the key, for example BACK, HOME, APP_SWITCH, ENTER, DPAD_DOWN',
      },
      code: {
        type: 'integer',
        description: 'The keycode number instead of a name, for example 4 for BACK - exactly one of key and code',
      },
      hold: holdParameter(
        'Holding a key down does not repeat it - send the key again for that - so this is for what '
        + 'the hold itself means to the platform.',
      ),
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      if (args?.key !== undefined && args?.code !== undefined) {
        throw new Error('lw_key takes either a key name or a code, not both')
      }
      if (args?.key === undefined && args?.code === undefined) {
        throw new Error('lw_key needs the key to press, by name (key) or by number (code)')
      }
      const held = holdMs(args?.hold)
      return formatPressed(await call('key', {
        displayId: args.displayId,
        key: args.key,
        code: args.code,
        holdMs: held,
      }), args.note)
    },
  }),

  defineTool({
    name: 'lw_swipe',
    description:
      'Drag a finger across a screen, as one continuous gesture: down at the first point, '
      + 'moved across, up at the second - on a virtual screen, or on the phone\'s own screen with '
      + 'displayId 0. Use it to scroll, to dismiss, or to drag something. The '
      + 'points are in the screen\'s own pixels and the duration decides the speed, which is what '
      + 'tells a scroll apart from a fling - a short duration over a long distance flings. A drag '
      + 'on the phone\'s own screen is the one gesture that can be taken away half way through: if '
      + 'the user puts a finger on the glass the finger is lifted where it had reached and the '
      + 'answer says how far it got, so do not read the next screenshot as the result of a gesture '
      + 'that finished - stop and ask the user.',
    parameters: {
      displayId: DISPLAY_ID,
      fromX: { type: 'number', required: true, description: 'Horizontal position the finger starts at' },
      fromY: { type: 'number', required: true, description: 'Vertical position the finger starts at' },
      toX: { type: 'number', required: true, description: 'Horizontal position the finger ends at' },
      toY: { type: 'number', required: true, description: 'Vertical position the finger ends at' },
      durationMs: {
        type: 'integer',
        description: `How long the drag takes in milliseconds, ${DEFAULT_SWIPE_MS} by default`,
      },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatGesture('swiped', await call('swipe', drop(args, 'note')), args?.note)
    },
  }),

  defineTool({
    name: 'lw_type',
    description:
      'Type text into a screen: search, fill a form, write a message. The device finds the field '
      + 'itself - the one with focus, or the only editable thing on the screen - and writes the '
      + 'text into it, so any character works, Chinese included. By default it adds at the cursor; '
      + 'pass replace to overwrite what the field already says instead. If the screen reports no '
      + 'field at all (a game, a canvas), the text goes in as key presses, which only produces what '
      + 'a keyboard has - letters, digits, punctuation - and lands wherever focus is. The answer '
      + 'says which of the two happened and what the field reads afterwards, so read it rather than '
      + 'assuming. Typing does not submit anything: press the app\'s own button, or ENTER with '
      + 'lw_key, if it needs that. On the phone\'s own screen it is refused while the user\'s finger '
      + 'is on it, like every other acting call.',
    parameters: {
      displayId: DISPLAY_ID,
      text: {
        type: 'string',
        required: true,
        description: 'The text to type, exactly as it should appear in the field',
      },
      replace: {
        type: 'boolean',
        description: 'Overwrite what the field already says, instead of adding to it at the cursor',
      },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const text = typeof args?.text === 'string' ? args.text : ''
      if (text === '') throw new Error('lw_type needs the text to type')
      return formatTyped(await call('type', drop(args, 'note')), args.note)
    },
  }),

  defineTool({
    name: 'lw_apps',
    description:
      'List the apps this device can start: the name a person sees, and the package to start it '
      + 'with. Pass query to filter by either one - that is how you find a single app rather than '
      + 'reading the whole list - and pass user to ask about a user other than the person holding '
      + 'the phone. A cloned app (双开, a parallel space) is the same package in another user rather '
      + 'than a second package, so it appears in that user\'s list. This is the listing a shell '
      + 'cannot give you: pm refuses an app uid on this device, and with an explicit --user it '
      + 'answers with LittleWhale alone, so call this instead of running pm or cmd package.',
    parameters: {
      user: {
        type: 'integer',
        description: 'The Android user to list for; the person holding the phone (0) when left out.'
          + ' A cloned app lives in another user, and lw_probe lists the users this device has',
      },
      query: {
        type: 'string',
        description: 'Only the apps whose name or package contains this, for example 设置 or bili',
      },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatApps(await call('apps', drop(args, 'note')))
    },
  }),

  defineTool({
    name: 'lw_launch',
    description:
      'Start an app on a screen and wait until it is up: on a virtual screen, or on the phone\'s '
      + 'own screen with displayId 0, which is what puts it in front of the person holding the '
      + 'phone. Name the app by package, or by the name a person uses for it ("设置") when that '
      + 'name reaches exactly one app - its '
      + 'launcher activity is what starts, and the answer says which one that turned out to be - '
      + 'or name an exact component like com.android.settings/.Settings. An app that is cloned - '
      + '双开, a parallel space - is the same package in another Android user rather than a second '
      + 'package, so it is started by naming that user, and lw_probe lists the ones this device '
      + 'has. Call lw_apps when you do not know the package. The app stays on that '
      + 'display until the screen is released, and the phone does not have to be showing it. For a '
      + 'game or another app that only knows one orientation, give the screen that shape first - '
      + 'lw_screen_create takes a width and a height - because an app that declares an orientation '
      + 'of its own keeps it and sits in a band in the middle of a screen shaped the other way. Use '
      + 'this rather than a shell: the am command refuses an app uid on this device, so a launch '
      + 'attempted from a shell fails. Call lw_ui or lw_screenshot after it to see the result.',
    parameters: {
      displayId: DISPLAY_ID,
      package: {
        type: 'string',
        description: 'Package to start, for example com.android.settings, or the name a person'
          + ' uses for the app when it reaches exactly one - lw_apps lists both',
      },
      component: {
        type: 'string',
        description: 'Exact component to start instead of a package, for example com.android.settings/.Settings',
      },
      user: {
        type: 'integer',
        description: 'The Android user to start it for; the person holding the phone (0) when left'
          + ' out. A cloned app - 双开, a parallel space - is the same package in another user,'
          + ' with its own data, and lw_probe lists the users this device has',
      },
      note: NOTE,
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      const packageName = (args?.package ?? '').trim()
      const component = (args?.component ?? '').trim()
      if ((packageName === '') === (component === '')) {
        throw new Error('lw_launch takes either a package or a component, and exactly one of them')
      }
      return formatLaunched(await call('launch', drop(args, 'note')), args?.note)
    },
  }),

  defineTool({
    name: 'lw_screenshot',
    description:
      'Take a picture of one screen and leave it as a PNG file in the workspace: a virtual screen, '
      + "or the phone's own screen with displayId 0, which is exactly what a person would be "
      + 'looking at. Works '
      + 'whether or not the phone is showing that screen, so it is how you see what is on a screen '
      + 'you are driving: read the file back with the read_image tool to look at it. Prefer lw_ui '
      + 'when it answers - it gives text and exact coordinates, and this gives neither - and use '
      + 'this for what has no readable tree: games, Flutter, canvases, or to see the picture as a '
      + 'person would. **The picture can come back much smaller than the screen**: the phone caps a '
      + 'screenshot at a pixel budget and then shrinks it further to fit a byte budget, so a '
      + '1080x2400 screen can arrive as 536x1192 - or smaller when there is a lot on it - and small '
      + 'text is then unreadable. **To read a screen, use the two reading tools rather than a '
      + 'picture of it**: lw_ui reads the accessibility tree, which gives every control\'s exact '
      + 'text and position, and lw_ocr runs the on-device OCR over the screen\'s own pixels without '
      + 'scaling them - so both are exact where a screenshot of the same screen is a small copy. '
      + 'Take this when you want to see the screen as a person would, or when neither of those two '
      + 'answers. When a scaled picture is what you have, the answer says what to multiply '
      + 'coordinates measured on it by to get coordinates for lw_tap and lw_swipe. The file is '
      + 'overwritten by the next capture of the same screen.',
    parameters: { displayId: DISPLAY_ID },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return formatScreenshot(await call('screenshot', drop(args)))
    },
  }),
]

/** One request, one response: the app answers a single line and closes the connection */
async function call(method, params = {}) {
  const endpoint = process.env[ENDPOINT_VARIABLE]
  const token = process.env[TOKEN_VARIABLE]
  if (!endpoint || !token) {
    throw new Error(
      `no privileged channel: ${ENDPOINT_VARIABLE} and ${TOKEN_VARIABLE} are unset,`
      + ' which means this host was not started by LittleWhale',
    )
  }
  const answer = await request(endpoint, JSON.stringify({ method, token, ...params }))
  let parsed
  try {
    parsed = JSON.parse(answer)
  } catch (error) {
    throw new Error(`the app answered with something that is not JSON: ${answer.slice(0, 200)}`)
  }
  if (parsed.ok !== true) throw new Error(parsed.error ?? 'the app reported no reason')
  return parsed.result
}

/** Send one line and resolve with the first line that comes back */
function request(endpoint, line) {
  const split = endpoint.lastIndexOf(':')
  const host = endpoint.slice(0, split)
  const port = Number(endpoint.slice(split + 1))
  return new Promise((resolve, reject) => {
    const socket = connect({ host, port })
    let buffer = ''
    let settled = false
    socket.setEncoding('utf8')
    socket.on('connect', () => socket.write(`${line}\n`))
    socket.on('data', (chunk) => {
      buffer += chunk
      const end = buffer.indexOf('\n')
      if (end < 0) return
      settled = true
      socket.end()
      resolve(buffer.slice(0, end))
    })
    socket.on('error', (error) => {
      settled = true
      reject(new Error(`could not reach the app's privileged channel at ${endpoint}: ${error.message}`))
    })
    socket.on('close', () => {
      if (settled) return
      settled = true
      reject(new Error(`the app closed the channel at ${endpoint} without answering`))
    })
  })
}

/** A tool's arguments as a request body, without the ones the caller left out or that are ours */
function drop(args, ...ours) {
  const skipped = new Set(ours)
  return Object.fromEntries(
    Object.entries(args ?? {}).filter(([key, value]) => value !== undefined && !skipped.has(key)),
  )
}

/**
 * How long something is to be held, as a number of milliseconds
 *
 * A string rather than a number because a bare number does not say what unit it is in: "1s",
 * "500ms", "1.5s" all work, and so do the three names above. A number that arrives anyway is read
 * as seconds, which is what "1" nearly always means
 *
 * @param value what the caller passed as hold, absent when it left it out
 * @param fallback what to answer when it did, which is "a plain press" for both tools
 */
function holdMs(value, fallback = 0) {
  if (value === undefined || value === null || value === '') return fallback
  const text = String(value).trim().toLowerCase()
  const names = Object.keys(HOLD_NAMES).join(' / ')
  if (Object.hasOwn(HOLD_NAMES, text)) return HOLD_NAMES[text]
  const parsed = /^(\d+(?:\.\d+)?)\s*(ms|s)?$/.exec(text)
  if (!parsed) {
    throw new Error(`hold has to be a duration like "1s" or "500ms", or one of ${names}`
      + ` - not ${JSON.stringify(value)}`)
  }
  const ms = Math.round(Number(parsed[1]) * (parsed[2] === 'ms' ? 1 : 1000))
  if (ms > MAX_HOLD_MS) {
    throw new Error(`hold of ${ms}ms is longer than this tool allows (${MAX_HOLD_MS}ms): the screen`
      + ' is held for the whole of it and nothing else can be done meanwhile')
  }
  return ms
}

/** The `hold` parameter of an acting tool, said once and shared by both of them */
function holdParameter(tail) {
  const names = Object.entries(HOLD_NAMES).map(([name, ms]) => `${name} (${ms}ms)`).join(', ')
  return {
    type: 'string',
    description: 'How long to hold it down: a duration like "1s" or "500ms", or one of '
      + `${names}. Android reads anything held 500ms or more as a long press, which is why the`
      + ' names start just above that line - short is the ordinary long press. Leave it out for a'
      + ` plain, instant press. ${tail}`,
  }
}

/** A duration as shortly as it can be said back: 600ms, 1.5s, 8s */
function formatMs(ms) {
  return ms >= 1000 ? `${Number((ms / 1000).toFixed(2))}s` : `${ms}ms`
}

/** How long a press was held, as a clause, or an empty string when it was not held */
function holdSaid(result) {
  const ms = result.holdMs ?? 0
  if (!(ms > 0)) return ''
  return `, held for ${formatMs(ms)}`
}

/**
 * The caller's own words about a step, put on the first line of its report
 *
 * It goes into the tool result rather than only into a log, because the result is what the session
 * keeps: a person reading the conversation afterwards sees why each press happened, right where it
 * happened
 */
function said(report, note) {
  const text = (note ?? '').trim()
  if (text === '') return report
  const end = report.indexOf('\n')
  return end < 0
    ? `${report} - "${text}"`
    : `${report.slice(0, end)} - "${text}"${report.slice(end)}`
}

/** The channel's own report, which says whether the device is reachable at all */
function formatProbe(result) {
  const channel = result.channel ?? {}
  const lines = []
  if (channel.connected === true) {
    const identity = channel.uid === 0 ? 'root' : `uid ${channel.uid}`
    lines.push(
      `privileged channel: ${channel.backend} as ${identity} (pid ${channel.pid}, service v${channel.version})`,
    )
  } else {
    lines.push(`privileged channel: not connected${channel.error ? ` - ${channel.error}` : ''}`)
  }

  const input = result.input ?? {}
  if (input.available !== true) {
    lines.push(`input devices: unreadable (${input.reason ?? 'no reason reported'})`)
  } else {
    lines.push(`input devices: ${input.deviceCount}`)
    const touch = input.touchscreen
    if (touch && typeof touch === 'object') {
      lines.push(
        `touchscreen: ${touch.path} "${touch.name}"`
        + ` x=[${touch.x[0]},${touch.x[1]}] y=[${touch.y[0]},${touch.y[1]}]`
        + ` protocolB=${touch.protocolB} direct=${touch.direct} btnTouch=${touch.touchKey}`,
      )
    } else {
      lines.push('touchscreen: none recognised')
    }
  }
  lines.push(brakeLine(result.touch))
  lines.push(...usersLines(result.users))
  return withJson(lines, result)
}

/**
 * The device's Android users, which is only worth saying when there is more than one
 *
 * One user is every phone as it comes, and saying so in every probe would be noise. A second one is
 * what a cloned app is - the same package installed again with its own data - so that is the case
 * the answer exists for: it is the number lw_launch has to be given to start the clone rather than
 * the app it was cloned from
 */
function usersLines(users) {
  const found = Array.isArray(users) ? users : []
  if (found.length < 2) return []
  const named = found.map(
    (user) => `${user.id} (${user.name}${user.running === false ? ', not running' : ''})`,
  )
  return [
    `users: ${named.join(', ')} - an app installed for two of these is two copies with their own`
    + ' data, so name the second one as lw_launch\'s user to start the copy rather than the original',
  ]
}

/** What the brake is doing, which is a fact about the whole device rather than about a screen
 *
 * It is read from the phone's own glass and it is the only thing standing between the model and
 * the screen in somebody's hand, so both the probe and the screen list say what it is up to
 */
function brakeLine(touch) {
  if (!touch || touch.available !== true) {
    const reason = touch && touch.error ? ` - ${touch.error}` : ''
    return `touch watch: not running${reason}, so nothing may act on displayId 0`
  }
  const state = touch.userTouching === true
    ? (touch.down === true
      ? 'the user is on the phone right now, a finger is down'
      : `the user was on the phone ${touch.msSinceLastTouch} ms ago`)
    : 'the phone is free right now'
  return `touch watch: ${touch.path}, ${touch.events} real touches seen, ${state}`
}

/** The screens that exist, which is the list every other call's displayId comes from */
function formatScreens(result) {
  const screens = result.screens ?? []
  const lines = []
  const primary = result.primary
  if (primary && typeof primary.displayId === 'number') {
    lines.push(
      `- displayId ${primary.displayId} "${primary.label}" `
      + `${primary.width}x${primary.height} at ${primary.dpi}dpi, the phone's own screen: always `
      + 'there, no preview, and the person holding the phone is the brake on it',
    )
  }
  if (screens.length === 0) {
    lines.push('no virtual screen exists yet, create one with lw_screen_create')
  } else {
    lines.push(`${screens.length} virtual screen${screens.length === 1 ? '' : 's'}`)
    for (const screen of screens) {
      const shown = screen.displayId === result.selected ? ', shown in the preview on the phone' : ''
      const paused = screen.acceptsControl === false ? ', control paused by the user' : ''
      lines.push(
        `- displayId ${screen.displayId} "${screen.label}" `
        + `${screen.width}x${screen.height} at ${screen.dpi}dpi${shown}${paused}`,
      )
    }
  }
  lines.push(brakeLine(result.touch))
  return withJson(lines, result)
}

/** What was just made */
function formatCreated(result) {
  if (result.created !== true) {
    return `the device refused to create a screen: ${result.error || 'no reason reported'}`
  }
  return withJson(
    [`created "${result.label}" as displayId ${result.displayId}, and the phone is showing it`],
    result,
  )
}

/** What a screen is now, after being given another shape */
function formatResized(result) {
  if (result.resized !== true) {
    return `nothing was resized: ${result.error || `no screen with displayId ${result.displayId}`}`
  }
  const how = result.swapped === true ? 'turned a quarter turn' : 'resized'
  return withJson(
    [
      `displayId ${result.displayId} "${result.label}" is now ${result.width}x${result.height}`
      + ` at ${result.dpi}dpi (${how}): anything on it that follows its screen has been laid out`
      + ' again, so take a new picture rather than reusing coordinates from the old one',
    ],
    result,
  )
}

/** What was just given back */
function formatReleased(result, note) {
  const lines = result.released === true
    ? [`released displayId ${result.displayId}`]
    : [`nothing was released: ${result.error || `no screen with displayId ${result.displayId}`}`]
  return said(withJson(lines, result), note)
}

/** One key press, said the way Android names it and the way the caller asked for it */
function formatPressed(result, note) {
  const how = result.longPress === true ? 'long-pressed' : 'pressed'
  return said(
    withJson(
      [
        `${how} ${result.key} (keycode ${result.code}) on displayId ${result.displayId}`
        + ` "${result.label}"${holdSaid(result)}`,
      ],
      result,
    ),
    note,
  )
}

/** What typing did, and what the field says now */
function formatTyped(result, note) {
  const where = result.via === 'keys'
    ? ' as key presses, because that screen reports no text field, so it went wherever focus is'
    : ` into ${fieldName(result.field)}`
  const count = `${result.written} character${result.written === 1 ? '' : 's'}`
  const lines = [
    `typed ${count}${where} on displayId ${result.displayId} "${result.label}"`,
  ]
  if (result.password === true) {
    lines.push('the field hides what is in it, so it is not read back')
  } else if (typeof result.text === 'string' && result.text !== '') {
    lines.push(`the field now reads ${JSON.stringify(result.text)}`)
  }
  return said(withJson(lines, result), note)
}

/** A field, named the way it says itself and placed where it is */
function fieldName(field) {
  if (!field || !field.className) return 'the field'
  const said = field.text || field.description
  return `${field.className}${said ? ` "${said}"` : ''} at ${formatRect(field.bounds)}`
}

/** What a gesture was delivered as, which is all a caller can know without looking */
function formatGesture(verb, result, note) {
  if (result.tapped === true || result.swiped === true) {
    const lines = result.swiped === true
      ? [`${verb} displayId ${result.displayId} over ${result.durationMs}ms`]
      : [`${verb} displayId ${result.displayId} at ${result.x},${result.y}${holdSaid(result)}`]
    // 熄屏时注入是**静默失效**的: 平台收下了事件, 界面没有任何反应, 所以这句话必须说在明面上
    if (result.screen && result.screen !== 'on') {
      lines.push(`careful: that screen is ${result.screen}, and a sleeping screen does not take`
        + ' injected presses - the gesture was delivered to a display that is not listening.'
        + ' Send WAKEUP with lw_key first if something was supposed to happen.')
    }
    return said(withJson(lines, result), note)
  }
  return said(withJson([`the device did not report the gesture as delivered`], result), note)
}

/** Where the picture went, and how to read coordinates off it */
function formatScreenshot(result) {
  if (!result.path) {
    return `no picture was written: ${result.error || 'no reason reported'}`
  }
  const kilobytes = (result.bytes / 1024).toFixed(1)
  const picture = result.picture ?? {}
  const size = picture.scale > 1
    ? `${picture.width}x${picture.height} px, so multiply coordinates measured on the picture by`
      + ` ${picture.scale.toFixed(2)} to get screen coordinates`
    : 'the same size as the screen'
  return withJson(
    [
      `displayId ${result.displayId} "${result.label}" captured to ${result.path}`
      + ` (screen ${result.width}x${result.height}, picture ${size}; ${kilobytes} KB, overwritten`
      + ' by the next capture of this screen)',
    ],
    result,
  )
}

/** What a screen says about itself, which is the answer that saves measuring anything */
function formatUi(result) {
  if (result.enabled !== true) {
    return 'the phone has the accessibility service off, so it will not report what is on a'
      + ' screen. Turn it on in LittleWhale\'s settings, under 无障碍. Until then use lw_screenshot'
      + ' and read the picture.'
  }
  if (result.error) {
    return `displayId ${result.displayId}: ${result.error}`
  }
  const nodes = result.nodes ?? []
  // A screen that draws its own picture does not report nothing: it reports one full-screen
  // surface with no text and nothing to press, which reads like a control to a caller that does
  // not look closely. What matters is whether anything here is worth acting on
  const usable = nodes.filter((node) => node.clickable === true
    || node.editable === true
    || node.checkable === true
    || node.scrollable === true
    || (node.text ?? '') !== '')
  if (usable.length === 0) {
    const surfaces = nodes
      .map((node) => `${node.className || 'control'}${node.description ? ` (${node.description})` : ''}`)
      .join(', ')
    return `displayId ${result.displayId} "${result.label}" draws its own picture: the device`
      + ` reports ${nodes.length} control${nodes.length === 1 ? '' : 's'} with no text and nothing`
      + ` to press${surfaces ? ` - ${surfaces}` : ''}. There is no name to press here, so use`
      + ' lw_screenshot and coordinates on this screen.'
  }
  const lines = [
    `displayId ${result.displayId} "${result.label}" ${result.width}x${result.height}, `
    + `${result.package || 'unknown package'}, ${usable.length} readable controls.`
    + ' The rectangles are in the screen\'s own pixels, the same ones lw_tap and lw_swipe use.',
  ]
  const shown = usable.slice(0, MAX_UI_NODES)
  for (const node of shown) lines.push(describeNode(node))
  if (usable.length > shown.length) {
    lines.push(
      `… ${usable.length - shown.length} more controls are not listed, they are off screen or far`
      + ' down the list; scroll the screen to bring what you need into view',
    )
  }
  lines.push('Press one of these by name with lw_tap(text="..."), or by a point from its rectangle.')
  return lines.join('\n')
}

/** One control, in one line: what it is, what to call it, and where it is */
function describeNode(node) {
  const indent = '  '.repeat(Math.min(node.depth ?? 0, 8))
  const parts = [node.className || 'control']
  if (node.text) parts.push(JSON.stringify(node.text))
  if (node.description) parts.push(`desc=${JSON.stringify(node.description)}`)
  if (node.viewId) parts.push(`id=${node.viewId}`)
  const flags = []
  if (node.clickable) flags.push('clickable')
  if (node.scrollable) flags.push('scrollable')
  if (node.editable) flags.push('editable')
  if (node.checkable) flags.push(node.checked ? 'checked' : 'checkable')
  if (flags.length) parts.push(`[${flags.join(' ')}]`)
  // A control that is not clickable is usually a label inside a row that is, so the row's
  // rectangle is what a point would have to land in
  if (node.target && !sameRect(node.target, node.bounds)) {
    parts.push(`at ${formatRect(node.bounds)} press row ${formatRect(node.target)}`)
  } else {
    parts.push(`at ${formatRect(node.bounds)}`)
  }
  return indent + parts.join(' ')
}

/** What a launch turned out to be, with the command's own words kept */
function formatLaunched(result, note) {
  const target = result.package || result.component
  const whose = result.user > 0 ? ` as user ${result.user}` : ''
  const from = result.asked ? ` (from ${JSON.stringify(result.asked)})` : ''
  const lines = result.started === true
    ? [`launched ${target}${from}${whose} on displayId ${result.displayId} "${result.label}"`]
    : [
      `the launch of ${target}${from}${whose} on displayId ${result.displayId} did not report`
      + ` success (am exited ${result.code}); what it said follows`,
    ]
  const output = (result.output ?? '').trim()
  if (output) lines.push(output)
  lines.push('Call lw_ui to see what the screen says now.')
  return said(lines.join('\n'), note)
}

/** What the launcher's apps are, as one line per app */
function formatApps(result) {
  const where = `user ${result.user}`
  if (result.error) return `could not list the apps for ${where}: ${result.error}`
  const apps = result.apps ?? []
  const wanted = result.query ? ` matching ${JSON.stringify(result.query)}` : ''
  if (apps.length === 0) {
    return result.query
      ? `no app for ${where} matches ${JSON.stringify(result.query)} (of ${result.launchable}` +
        ' that can be started) - try a shorter query, or no query at all to see them'
      : `nothing installed for ${where} has a launcher entry, so there is nothing here to start`
  }
  const lines = [
    `${apps.length} app${apps.length === 1 ? '' : 's'}${wanted} for ${where}` +
      (result.truncated ? ' (the list was cut short, narrow it with query)' : '') + ':',
  ]
  for (const app of apps) lines.push(`  ${app.label} - ${app.package}`)
  lines.push('Start one with lw_launch(package="..."), which takes the package or that name.')
  return lines.join('\n')
}

/** What pressing by name did, which is the one call that can be ambiguous */
function formatNamedTap(result, note) {
  return said(namedTapReport(result), note)
}

/** What reading a screen's pixels found */
function formatOcr(result) {
  const where = `displayId ${result.displayId}`
  if (result.error) return `could not read ${where}: ${result.error}`
  const lines = result.lines ?? []
  const cost = `${result.captureMs ?? 0}ms to capture, ${result.detMs ?? 0}ms to find the text, `
    + `${result.recMs ?? 0}ms per line, on ${result.backend}`
  const asleep = asleepLine(result)
  if (lines.length === 0) {
    return `no text was found on ${where} (${cost}). The screen may be blank or hold only`
      + ' pictures, and lw_screenshot shows it as it is.'
  }
  const report = [
    `${lines.length} line${lines.length === 1 ? '' : 's'} read on ${where} (${cost}). The`
    + ' rectangles are in the screen\'s own pixels, the same ones lw_tap and lw_swipe use: press a'
    + ' line with lw_tap(x=..., y=...) at its centre.',
  ]
  if (asleep) report.unshift(asleep)
  for (const line of lines) {
    const box = line.box ?? []
    report.push(`  [${box.join(',')}] ${JSON.stringify(line.text)}`
      + ` score ${(line.score ?? 0).toFixed(2)} centre ${(line.center ?? []).join(',')}`)
  }
  report.push('A low score means it was probably not text - an icon, a picture or a stray mark.')
  report.push('Press one of these by name with lw_tap(text="...") and no coordinate at all, or by'
    + ' a point from its rectangle; pressing by name lands on a random point inside the rectangle'
    + ' rather than its exact centre.')
  return report.join('\n')
}

/**
 * The warning for a screen that is not awake, or null when it is
 *
 * A sleeping display still hands out the last frame it drew and still swallows injected touches,
 * both without saying so: what comes back reads like a live screen that ignores presses
 */
function asleepLine(result) {
  const state = result.screen
  if (!state || state === 'on') return null
  return `Careful: this screen is ${state}, and a sleeping screen goes on showing its last frame`
    + ' while ignoring injected presses. What is listed below may no longer be on the screen, and'
    + ' none of it can be pressed. Wake it first with lw_key(key="WAKEUP") and read it again.'
}

/**
 * Press a name that exists only as pixels
 *
 * Reading is a guess, so it never guesses twice: an exact match wins, otherwise a line that
 * contains the name, and more than one candidate presses nothing at all
 */
async function tapByReading(displayId, text, hold = 0) {
  const result = await call('ocr', { displayId })
  if (result.error) return `the screen could not be read: ${result.error}`
  if (result.screen && result.screen !== 'on') {
    return `the screen is ${result.screen}, so nothing was pressed: a sleeping screen keeps showing`
      + ' its last frame but does not take a press. Send WAKEUP with lw_key first, then try again.'
  }
  const lines = result.lines ?? []
  const needle = String(text).trim()
  const exact = lines.filter((line) => String(line.text ?? '').trim() === needle)
  const candidates = exact.length > 0
    ? exact
    : lines.filter((line) => String(line.text ?? '').includes(needle))
  if (candidates.length === 0) {
    return lines.length === 0
      ? 'reading the screen found no text at all'
      : `reading the screen found no line saying ${JSON.stringify(text)} among the ${lines.length}`
        + ' lines it could read; call lw_ocr to see them'
  }
  if (candidates.length > 1) {
    return `${candidates.length} lines on the screen say that, so nothing was pressed: `
      + candidates.map((line) => `${JSON.stringify(line.text)} at [${(line.box ?? []).join(',')}]`)
        .join(', ')
      + '. Press the one you meant with lw_tap(x=..., y=...) at its centre.'
  }
  const only = candidates[0]
  const point = scatterInside(only.box ?? [])
  const pressed = await call('tap', { displayId, x: point.x, y: point.y, holdMs: hold })
  return `pressed the line ${JSON.stringify(only.text)} (read off the screen with score`
    + ` ${(only.score ?? 0).toFixed(2)}) at ${point.x},${point.y} on displayId`
    + ` ${pressed.displayId}, a point drawn inside its rectangle [${(only.box ?? []).join(',')}]`
    + ` rather than its exact centre${hold > 0 ? `, holding it for ${formatMs(hold)}` : ''}`
}

/**
 * A point inside a rectangle, drawn at random rather than being the centre
 *
 * Two reasons. The centre of a wide row is often the label inside it rather than the part that
 * takes the press, while anywhere in the row works. And a tool that lands on the same coordinate
 * every single time is a pattern: a game that watches its own input can tell it apart from a
 * person, and a person never taps the same pixel twice
 *
 * The draw is confined to the middle of the rectangle - a fifth of the way in from each edge - so
 * it stays clear of the border and of whatever sits next to the row
 */
function scatterInside(box) {
  const [left, top, right, bottom] = box
  const insetX = (right - left) * SCATTER_INSET
  const insetY = (bottom - top) * SCATTER_INSET
  const x = Math.round(left + insetX + Math.random() * Math.max(0, right - left - 2 * insetX))
  const y = Math.round(top + insetY + Math.random() * Math.max(0, bottom - top - 2 * insetY))
  return { x, y }
}

/** The report itself, before the caller's own note is put on it */
function namedTapReport(result) {
  const matches = result.matches ?? []
  // clicked 而不是 outcome: 无障碍动作被拒之后那一根按住的手指算数, 见 lw_tap 里的注释
  if (result.clicked === true) {
    const hit = matches[0] ?? {}
    const how = result.via === 'finger'
      ? 'a touch at its centre, because it does not take an accessibility action'
      : result.long === true ? 'its own long-click action' : 'its own click action'
    return `pressed ${nameOf(hit)} on displayId ${result.displayId} by ${how}${holdSaid(result)}`
  }
  if (result.outcome === 'ambiguous') {
    return [
      `${matches.length} controls on displayId ${result.displayId} say that, so nothing was pressed:`
      + ' this tool does not guess which one you meant. Pick one from this list and press it by a'
      + ' point inside its rectangle with lw_tap(x=..., y=...), or call lw_ui and name a control'
      + ' that only appears once.',
      ...matches.map((node) => `- ${nameOf(node)} at ${formatRect(node.bounds)}`
        + (node.target && !sameRect(node.target, node.bounds)
          ? `, the row that takes the press is ${formatRect(node.target)}`
          : '')),
    ].join('\n')
  }
  if (result.outcome === 'none') {
    return `nothing matched: ${result.error || 'no reason reported'}. Call lw_ui to see what the`
      + ' screen actually says.'
  }
  if (result.outcome === 'unavailable') {
    return result.error || 'the accessibility service is off, so nothing can be pressed by name'
  }
  return `the press did not go through: ${result.error || result.outcome}`
}

/** How a control is named back to a caller, preferring the text it was found by */
function nameOf(node) {
  const label = node.text || node.description
  const id = node.viewId ? ` id=${node.viewId}` : ''
  return `${node.className || 'control'}${id} ${label ? JSON.stringify(label) : '(no text)'}`
}

/** A rectangle as it is easiest to read, which is also how a point is picked out of it */
function formatRect(rect) {
  if (!Array.isArray(rect) || rect.length < 4) return '[?]'
  return `[${rect[0]},${rect[1]},${rect[2]},${rect[3]}]`
}

function sameRect(a, b) {
  return Array.isArray(a) && Array.isArray(b) && a.every((value, index) => value === b[index])
}

/** A report a model can read, with the structured answer kept underneath it */
function withJson(lines, result) {
  return [...lines, '', JSON.stringify(result, null, 2)].join('\n')
}
