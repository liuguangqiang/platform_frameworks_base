/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.transition;

import android.content.Context;
import android.util.ArrayMap;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * This class manages the set of transitions that fire when there is a
 * change of {@link Scene}. To use the manager, add scenes along with
 * transition objects with calls to {@link #setTransition(Scene, Transition)}
 * or {@link #setTransition(Scene, Scene, Transition)}. Setting specific
 * transitions for scene changes is not required; by default, a Scene change
 * will use {@link AutoTransition} to do something reasonable for most
 * situations. Specifying other transitions for particular scene changes is
 * only necessary if the application wants different transition behavior
 * in these situations.
 *
 * <p>TransitionManagers can be declared in XML resource files inside the
 * <code>res/transition</code> directory. TransitionManager resources consist of
 * the <code>transitionManager</code>tag name, containing one or more
 * <code>transition</code> tags, each of which describe the relationship of
 * that transition to the from/to scene information in that tag.
 * For example, here is a resource file that declares several scene
 * transitions:</p>
 *
 * {@sample development/samples/ApiDemos/res/transition/transitions_mgr.xml TransitionManager}
 *
 * <p>For each of the <code>fromScene</code> and <code>toScene</code> attributes,
 * there is a reference to a standard XML layout file. This is equivalent to
 * creating a scene from a layout in code by calling
 * {@link Scene#getSceneForLayout(ViewGroup, int, Context)}. For the
 * <code>transition</code> attribute, there is a reference to a resource
 * file in the <code>res/transition</code> directory which describes that
 * transition.</p>
 *
 * Information on XML resource descriptions for transitions can be found for
 * {@link android.R.styleable#Transition}, {@link android.R.styleable#TransitionSet},
 * {@link android.R.styleable#TransitionTarget}, {@link android.R.styleable#Fade},
 * and {@link android.R.styleable#TransitionManager}.
 */
public class TransitionManager {
    // TODO: how to handle enter/exit?

    private static String LOG_TAG = "TransitionManager";

    private static Transition sDefaultTransition = new AutoTransition();

    private static final String[] EMPTY_STRINGS = new String[0];

    ArrayMap<Scene, Transition> mSceneTransitions = new ArrayMap<Scene, Transition>();
    ArrayMap<Scene, ArrayMap<Scene, Transition>> mScenePairTransitions =
            new ArrayMap<Scene, ArrayMap<Scene, Transition>>();
    ArrayMap<Scene, ArrayMap<String, Transition>> mSceneNameTransitions =
            new ArrayMap<Scene, ArrayMap<String, Transition>>();
    ArrayMap<String, ArrayMap<Scene, Transition>> mNameSceneTransitions =
            new ArrayMap<String, ArrayMap<Scene, Transition>>();
    private static ThreadLocal<WeakReference<ArrayMap<ViewGroup, ArrayList<Transition>>>>
            sRunningTransitions =
            new ThreadLocal<WeakReference<ArrayMap<ViewGroup, ArrayList<Transition>>>>();
    private static ArrayList<ViewGroup> sPendingTransitions = new ArrayList<ViewGroup>();


    /**
     * Sets the transition to be used for any scene change for which no
     * other transition is explicitly set. The initial value is
     * an {@link AutoTransition} instance.
     *
     * @param transition The default transition to be used for scene changes.
     *
     * @hide pending later changes
     */
    public void setDefaultTransition(Transition transition) {
        sDefaultTransition = transition;
    }

    /**
     * Gets the current default transition. The initial value is an {@link
     * AutoTransition} instance.
     *
     * @return The current default transition.
     * @see #setDefaultTransition(Transition)
     *
     * @hide pending later changes
     */
    public static Transition getDefaultTransition() {
        return sDefaultTransition;
    }

    /**
     * Sets a specific transition to occur when the given scene is entered.
     *
     * @param scene The scene which, when applied, will cause the given
     * transition to run.
     * @param transition The transition that will play when the given scene is
     * entered. A value of null will result in the default behavior of
     * using the default transition instead.
     */
    public void setTransition(Scene scene, Transition transition) {
        mSceneTransitions.put(scene, transition);
    }

    /**
     * Sets a specific transition to occur when the given pair of scenes is
     * exited/entered.
     *
     * @param fromScene The scene being exited when the given transition will
     * be run
     * @param toScene The scene being entered when the given transition will
     * be run
     * @param transition The transition that will play when the given scene is
     * entered. A value of null will result in the default behavior of
     * using the default transition instead.
     */
    public void setTransition(Scene fromScene, Scene toScene, Transition transition) {
        ArrayMap<Scene, Transition> sceneTransitionMap = mScenePairTransitions.get(toScene);
        if (sceneTransitionMap == null) {
            sceneTransitionMap = new ArrayMap<Scene, Transition>();
            mScenePairTransitions.put(toScene, sceneTransitionMap);
        }
        sceneTransitionMap.put(fromScene, transition);
    }

    /**
     * Returns the Transition for the given scene being entered. The result
     * depends not only on the given scene, but also the scene which the
     * {@link Scene#getSceneRoot() sceneRoot} of the Scene is currently in.
     *
     * @param scene The scene being entered
     * @return The Transition to be used for the given scene change. If no
     * Transition was specified for this scene change, the default transition
     * will be used instead.
     */
    private Transition getTransition(Scene scene) {
        Transition transition = null;
        ViewGroup sceneRoot = scene.getSceneRoot();
        if (sceneRoot != null) {
            // TODO: cached in Scene instead? long-term, cache in View itself
            Scene currScene = Scene.getCurrentScene(sceneRoot);
            if (currScene != null) {
                ArrayMap<Scene, Transition> sceneTransitionMap = mScenePairTransitions.get(scene);
                if (sceneTransitionMap != null) {
                    transition = sceneTransitionMap.get(currScene);
                    if (transition != null) {
                        return transition;
                    }
                }
            }
        }
        transition = mSceneTransitions.get(scene);
        return (transition != null) ? transition : sDefaultTransition;
    }

    /**
     * This is where all of the work of a transition/scene-change is
     * orchestrated. This method captures the start values for the given
     * transition, exits the current Scene, enters the new scene, captures
     * the end values for the transition, and finally plays the
     * resulting values-populated transition.
     *
     * @param scene The scene being entered
     * @param transition The transition to play for this scene change
     */
    private static void changeScene(Scene scene, Transition transition) {

        final ViewGroup sceneRoot = scene.getSceneRoot();

        Transition transitionClone = transition.clone();
        transitionClone.setSceneRoot(sceneRoot);

        Scene oldScene = Scene.getCurrentScene(sceneRoot);
        if (oldScene != null && oldScene.isCreatedFromLayoutResource()) {
            transitionClone.setCanRemoveViews(true);
        }

        sceneChangeSetup(sceneRoot, transitionClone);

        scene.enter();

        sceneChangeRunTransition(sceneRoot, transitionClone);
    }

    private static ArrayMap<ViewGroup, ArrayList<Transition>> getRunningTransitions() {
        WeakReference<ArrayMap<ViewGroup, ArrayList<Transition>>> runningTransitions =
                sRunningTransitions.get();
        if (runningTransitions == null || runningTransitions.get() == null) {
            ArrayMap<ViewGroup, ArrayList<Transition>> transitions =
                    new ArrayMap<ViewGroup, ArrayList<Transition>>();
            runningTransitions = new WeakReference<ArrayMap<ViewGroup, ArrayList<Transition>>>(
                    transitions);
            sRunningTransitions.set(runningTransitions);
        }
        return runningTransitions.get();
    }

    private static void sceneChangeRunTransition(final ViewGroup sceneRoot,
            final Transition transition) {
        if (transition != null && sceneRoot != null) {
            MultiListener listener = new MultiListener(transition, sceneRoot);
            sceneRoot.addOnAttachStateChangeListener(listener);
            sceneRoot.getViewTreeObserver().addOnPreDrawListener(listener);
        }
    }

    /**
     * Retrieve the transition from a named scene to a target defined scene if one has been
     * associated with this TransitionManager.
     *
     * <p>A named scene is an indirect link for a transition. Fundamentally a named
     * scene represents a potentially arbitrary intersection point of two otherwise independent
     * transitions. Activity A may define a transition from scene X to "com.example.scene.FOO"
     * while activity B may define a transition from scene "com.example.scene.FOO" to scene Y.
     * In this way applications may define an API for more sophisticated transitions between
     * caller and called activities very similar to the way that <code>Intent</code> extras
     * define APIs for arguments and data propagation between activities.</p>
     *
     * @param fromName Named scene that this transition corresponds to
     * @param toScene Target scene that this transition will move to
     * @return Transition corresponding to the given fromName and toScene or null
     *         if no association exists in this TransitionManager
     *
     * @see #setTransition(String, Scene, Transition)
     */
    public Transition getNamedTransition(String fromName, Scene toScene) {
        ArrayMap<Scene, Transition> m = mNameSceneTransitions.get(fromName);
        if (m != null) {
            return m.get(toScene);
        }
        return null;
    }

    /**
     * Retrieve the transition from a defined scene to a target named scene if one has been
     * associated with this TransitionManager.
     *
     * <p>A named scene is an indirect link for a transition. Fundamentally a named
     * scene represents a potentially arbitrary intersection point of two otherwise independent
     * transitions. Activity A may define a transition from scene X to "com.example.scene.FOO"
     * while activity B may define a transition from scene "com.example.scene.FOO" to scene Y.
     * In this way applications may define an API for more sophisticated transitions between
     * caller and called activities very similar to the way that <code>Intent</code> extras
     * define APIs for arguments and data propagation between activities.</p>
     *
     * @param fromScene Scene that this transition starts from
     * @param toName Name of the target scene
     * @return Transition corresponding to the given fromScene and toName or null
     *         if no association exists in this TransitionManager
     */
    public Transition getNamedTransition(Scene fromScene, String toName) {
        ArrayMap<String, Transition> m = mSceneNameTransitions.get(fromScene);
        if (m != null) {
            return m.get(toName);
        }
        return null;
    }

    /**
     * Retrieve the supported target named scenes when transitioning away from the given scene.
     *
     * <p>A named scene is an indirect link for a transition. Fundamentally a named
     * scene represents a potentially arbitrary intersection point of two otherwise independent
     * transitions. Activity A may define a transition from scene X to "com.example.scene.FOO"
     * while activity B may define a transition from scene "com.example.scene.FOO" to scene Y.
     * In this way applications may define an API for more sophisticated transitions between
     * caller and called activities very similar to the way that <code>Intent</code> extras
     * define APIs for arguments and data propagation between activities.</p>
     *
     * @param fromScene Scene to transition from
     * @return An array of Strings naming each supported transition starting from
     *         <code>fromScene</code>. If no transitions to a named scene from the given
     *         scene are supported this function will return a String[] of length 0.
     *
     * @see #setTransition(Scene, String, Transition)
     */
    public String[] getTargetSceneNames(Scene fromScene) {
        final ArrayMap<String, Transition> m = mSceneNameTransitions.get(fromScene);
        if (m == null) {
            return EMPTY_STRINGS;
        }
        final int count = m.size();
        final String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            result[i] = m.keyAt(i);
        }
        return result;
    }

    /**
     * Set a transition from a specific scene to a named scene.
     *
     * <p>A named scene is an indirect link for a transition. Fundamentally a named
     * scene represents a potentially arbitrary intersection point of two otherwise independent
     * transitions. Activity A may define a transition from scene X to "com.example.scene.FOO"
     * while activity B may define a transition from scene "com.example.scene.FOO" to scene Y.
     * In this way applications may define an API for more sophisticated transitions between
     * caller and called activities very similar to the way that <code>Intent</code> extras
     * define APIs for arguments and data propagation between activities.</p>
     *
     * @param fromScene Scene to transition from
     * @param toName Named scene to transition to
     * @param transition Transition to use
     *
     * @see #getTargetSceneNames(Scene)
     */
    public void setTransition(Scene fromScene, String toName, Transition transition) {
        ArrayMap<String, Transition> m = mSceneNameTransitions.get(fromScene);
        if (m == null) {
            m = new ArrayMap<String, Transition>();
            mSceneNameTransitions.put(fromScene, m);
        }
        m.put(toName, transition);
    }

    /**
     * Set a transition from a named scene to a concrete scene.
     *
     * <p>A named scene is an indirect link for a transition. Fundamentally a named
     * scene represents a potentially arbitrary intersection point of two otherwise independent
     * transitions. Activity A may define a transition from scene X to "com.example.scene.FOO"
     * while activity B may define a transition from scene "com.example.scene.FOO" to scene Y.
     * In this way applications may define an API for more sophisticated transitions between
     * caller and called activities very similar to the way that <code>Intent</code> extras
     * define APIs for arguments and data propagation between activities.</p>
     *
     * @param fromName Named scene to transition from
     * @param toScene Scene to transition to
     * @param transition Transition to use
     *
     * @see #getNamedTransition(String, Scene)
     */
    public void setTransition(String fromName, Scene toScene, Transition transition) {
        ArrayMap<Scene, Transition> m = mNameSceneTransitions.get(fromName);
        if (m == null) {
            m = new ArrayMap<Scene, Transition>();
            mNameSceneTransitions.put(fromName, m);
        }
        m.put(toScene, transition);
    }

    /**
     * This private utility class is used to listen for both OnPreDraw and
     * OnAttachStateChange events. OnPreDraw events are the main ones we care
     * about since that's what triggers the transition to take place.
     * OnAttachStateChange events are also important in case the view is removed
     * from the hierarchy before the OnPreDraw event takes place; it's used to
     * clean up things since the OnPreDraw listener didn't get called in time.
     */
    private static class MultiListener implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {

        Transition mTransition;
        ViewGroup mSceneRoot;

        MultiListener(Transition transition, ViewGroup sceneRoot) {
            mTransition = transition;
            mSceneRoot = sceneRoot;
        }

        private void removeListeners() {
            mSceneRoot.getViewTreeObserver().removeOnPreDrawListener(this);
            mSceneRoot.removeOnAttachStateChangeListener(this);
        }

        @Override
        public void onViewAttachedToWindow(View v) {
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            removeListeners();

            sPendingTransitions.remove(mSceneRoot);
            ArrayList<Transition> runningTransitions = getRunningTransitions().get(mSceneRoot);
            if (runningTransitions != null && runningTransitions.size() > 0) {
                for (Transition runningTransition : runningTransitions) {
                    runningTransition.resume();
                }
            }
            mTransition.clearValues(true);
        }

        @Override
        public boolean onPreDraw() {
            removeListeners();
            sPendingTransitions.remove(mSceneRoot);
            // Add to running list, handle end to remove it
            final ArrayMap<ViewGroup, ArrayList<Transition>> runningTransitions =
                    getRunningTransitions();
            ArrayList<Transition> currentTransitions = runningTransitions.get(mSceneRoot);
            ArrayList<Transition> previousRunningTransitions = null;
            if (currentTransitions == null) {
                currentTransitions = new ArrayList<Transition>();
                runningTransitions.put(mSceneRoot, currentTransitions);
            } else if (currentTransitions.size() > 0) {
                previousRunningTransitions = new ArrayList<Transition>(currentTransitions);
            }
            currentTransitions.add(mTransition);
            mTransition.addListener(new Transition.TransitionListenerAdapter() {
                @Override
                public void onTransitionEnd(Transition transition) {
                    ArrayList<Transition> currentTransitions =
                            runningTransitions.get(mSceneRoot);
                    currentTransitions.remove(transition);
                }
            });
            mTransition.captureValues(mSceneRoot, false);
            if (previousRunningTransitions != null) {
                for (Transition runningTransition : previousRunningTransitions) {
                    runningTransition.resume();
                }
            }
            mTransition.playTransition(mSceneRoot);

            return true;
        }
    };

    private static void sceneChangeSetup(ViewGroup sceneRoot, Transition transition) {

        // Capture current values
        ArrayList<Transition> runningTransitions = getRunningTransitions().get(sceneRoot);

        if (runningTransitions != null && runningTransitions.size() > 0) {
            for (Transition runningTransition : runningTransitions) {
                runningTransition.pause();
            }
        }

        if (transition != null) {
            transition.captureValues(sceneRoot, true);
        }

        // Notify previous scene that it is being exited
        Scene previousScene = Scene.getCurrentScene(sceneRoot);
        if (previousScene != null) {
            previousScene.exit();
        }
    }

    /**
     * Change to the given scene, using the
     * appropriate transition for this particular scene change
     * (as specified to the TransitionManager, or the default
     * if no such transition exists).
     *
     * @param scene The Scene to change to
     */
    public void transitionTo(Scene scene) {
        // Auto transition if there is no transition declared for the Scene, but there is
        // a root or parent view
        changeScene(scene, getTransition(scene));
    }

    /**
     * Convenience method to simply change to the given scene using
     * the default transition for TransitionManager.
     *
     * @param scene The Scene to change to
     */
    public static void go(Scene scene) {
        changeScene(scene, sDefaultTransition);
    }

    /**
     * Convenience method to simply change to the given scene using
     * the given transition.
     *
     * <p>Passing in <code>null</code> for the transition parameter will
     * result in the scene changing without any transition running, and is
     * equivalent to calling {@link Scene#exit()} on the scene root's
     * current scene, followed by {@link Scene#enter()} on the scene
     * specified by the <code>scene</code> parameter.</p>
     *
     * @param scene The Scene to change to
     * @param transition The transition to use for this scene change. A
     * value of null causes the scene change to happen with no transition.
     */
    public static void go(Scene scene, Transition transition) {
        changeScene(scene, transition);
    }

    /**
     * Convenience method to animate, using the default transition,
     * to a new scene defined by all changes within the given scene root between
     * calling this method and the next rendering frame.
     * Equivalent to calling {@link #beginDelayedTransition(ViewGroup, Transition)}
     * with a value of <code>null</code> for the <code>transition</code> parameter.
     *
     * @param sceneRoot The root of the View hierarchy to run the transition on.
     */
    public static void beginDelayedTransition(final ViewGroup sceneRoot) {
        beginDelayedTransition(sceneRoot, null);
    }

    /**
     * Convenience method to animate to a new scene defined by all changes within
     * the given scene root between calling this method and the next rendering frame.
     * Calling this method causes TransitionManager to capture current values in the
     * scene root and then post a request to run a transition on the next frame.
     * At that time, the new values in the scene root will be captured and changes
     * will be animated. There is no need to create a Scene; it is implied by
     * changes which take place between calling this method and the next frame when
     * the transition begins.
     *
     * <p>Calling this method several times before the next frame (for example, if
     * unrelated code also wants to make dynamic changes and run a transition on
     * the same scene root), only the first call will trigger capturing values
     * and exiting the current scene. Subsequent calls to the method with the
     * same scene root during the same frame will be ignored.</p>
     *
     * <p>Passing in <code>null</code> for the transition parameter will
     * cause the TransitionManager to use its default transition.</p>
     *
     * @param sceneRoot The root of the View hierarchy to run the transition on.
     * @param transition The transition to use for this change. A
     * value of null causes the TransitionManager to use the default transition.
     */
    public static void beginDelayedTransition(final ViewGroup sceneRoot, Transition transition) {
        if (!sPendingTransitions.contains(sceneRoot) && sceneRoot.isLaidOut()) {
            if (Transition.DBG) {
                Log.d(LOG_TAG, "beginDelayedTransition: root, transition = " +
                        sceneRoot + ", " + transition);
            }
            sPendingTransitions.add(sceneRoot);
            if (transition == null) {
                transition = sDefaultTransition;
            }
            final Transition transitionClone = transition.clone();
            sceneChangeSetup(sceneRoot, transitionClone);
            Scene.setCurrentScene(sceneRoot, null);
            sceneChangeRunTransition(sceneRoot, transitionClone);
        }
    }
}
