/*-
 * #%L
 * N5 Viewer
 * %%
 * Copyright (C) 2017 - 2022 Igor Pisarev, Stephan Saalfeld
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package org.janelia.saalfeldlab.n5.bdv;

import java.awt.Checkbox;
import java.awt.Panel;
import java.awt.TextField;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.ActionMap;
import javax.swing.InputMap;

import org.scijava.ui.behaviour.Behaviour;
import org.scijava.ui.behaviour.BehaviourMap;
import org.scijava.ui.behaviour.ClickBehaviour;
import org.scijava.ui.behaviour.InputTriggerAdder;
import org.scijava.ui.behaviour.InputTriggerMap;
import org.scijava.ui.behaviour.KeyStrokeAdder;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.InputActionBindings;

import bdv.viewer.Source;
import bdv.viewer.ViewerPanel;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 * @author John Bogovic &lt;bogovicj@janelia.hhmi.org&gt;
 */
public class CropController<T extends NumericType<T> & NativeType<T>> {

	final protected ViewerPanel viewer;

	private RealPoint lastClick = new RealPoint(3);
	private List<? extends Source<T>> sources;

	static private int width = 1024;
	static private int height = 1024;
	static private int depth = 512;
	static private int scaleLevel = 0;
	static private boolean single4DStack = true;

	// for behavioUrs
	private final BehaviourMap behaviourMap = new BehaviourMap();
	private final InputTriggerMap inputTriggerMap = new InputTriggerMap();
	private final InputTriggerAdder inputAdder;

	// for keystroke actions
	private final ActionMap ksActionMap = new ActionMap();
	private final InputMap ksInputMap = new InputMap();

	public CropController(
			final ViewerPanel viewer,
			final List<? extends Source<T>> sources,
			final InputTriggerConfig config,
			final InputActionBindings inputActionBindings,
			final KeyStrokeAdder.Factory keyProperties) {

		this.viewer = viewer;
		this.sources = sources;

		inputAdder = config.inputTriggerAdder(inputTriggerMap, "cropLegacy");

		new Crop("cropLegacy", "ctrl SPACE").register();

		inputActionBindings.addActionMap("select", ksActionMap);
		inputActionBindings.addInputMap("select", ksInputMap);
	}

	////////////////
	// behavioUrs //
	////////////////

	public BehaviourMap getBehaviourMap() {

		return behaviourMap;
	}

	public InputTriggerMap getInputTriggerMap() {

		return inputTriggerMap;
	}

	private abstract class SelfRegisteringBehaviour implements Behaviour {

		private final String name;
		private final String[] defaultTriggers;

		public SelfRegisteringBehaviour(final String name, final String... defaultTriggers) {

			this.name = name;
			this.defaultTriggers = defaultTriggers;
		}

		public void register() {

			behaviourMap.put(name, this);
			inputAdder.put(name, defaultTriggers);
		}
	}

	private class Crop extends SelfRegisteringBehaviour implements ClickBehaviour {

		private List<TextField> centerPointTextFields;
		private long[] centerPoint;

		public Crop(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		public void click(final int x, final int y) {

			viewer.displayToGlobalCoordinates(x, y, lastClick);

			centerPoint = new long[lastClick.numDimensions()];
			for (int i = 0; i < centerPoint.length; ++i)
				centerPoint[i] = Math.round(lastClick.getDoublePosition(i));

			final GenericDialog gd = new GenericDialog("Crop");

			gd.addCheckbox("Custom_center_point", false);
			gd.addNumericField("X : ", centerPoint[0], 0);
			gd.addNumericField("Y : ", centerPoint[1], 0);
			gd.addNumericField("Z : ", centerPoint[2], 0);
			gd.addPanel(new Panel());
			gd.addNumericField("width : ", width, 0, 5, "px");
			gd.addNumericField("height : ", height, 0, 5, "px");
			gd.addNumericField("depth : ", depth, 0, 5, "px");
			gd.addNumericField("scale_level : ", scaleLevel, 0);
			gd.addCheckbox("Single_4D_stack", single4DStack);

			centerPointTextFields = new ArrayList<>();
			for (int i = 0; i < 3; ++i)
				centerPointTextFields.add((TextField)gd.getNumericFields().get(i));

			final Checkbox centerPointCheckbox = (Checkbox)gd.getCheckboxes().get(0);
			centerPointCheckbox
					.addItemListener(
							new ItemListener() {

								@Override
								public void itemStateChanged(final ItemEvent e) {

									setCenterPointTextFieldsEnabled(e.getStateChange() == ItemEvent.SELECTED);
								}
							});

			gd.addComponentListener(new ComponentAdapter() {

				@Override
				public void componentShown(final ComponentEvent e) {

					setCenterPointTextFieldsEnabled(false);
				}
			});

			gd.showDialog();

			if (gd.wasCanceled())
				return;

			final boolean customCenterPoint = gd.getNextBoolean();
			for (int i = 0; i < 3; ++i)
				centerPoint[i] = (long)gd.getNextNumber();

			width = (int)gd.getNextNumber();
			height = (int)gd.getNextNumber();
			depth = (int)gd.getNextNumber();
			scaleLevel = (int)gd.getNextNumber();
			single4DStack = gd.getNextBoolean();

			final int w = width;
			final int h = height;
			final int d = depth;
			final int s = scaleLevel;

			final List<RandomAccessibleInterval<T>> channelsImages = new ArrayList<>();
			long[] min = null;

			final String centerPosStr = Arrays.toString(centerPoint);
			if (customCenterPoint)
				lastClick.setPosition(centerPoint);

			final int timepoint = 1;
			AffineTransform3D firstTransform = null;
			for (int channel = 0; channel < sources.size(); ++channel) {
				final Source<T> source = sources.get(channel);

				if (s < 0 || s >= source.getNumMipmapLevels()) {
					IJ
							.log(
									String
											.format(
													"Specified incorrect scale level %d. Valid range is [%d, %d]",
													s,
													0,
													source.getNumMipmapLevels() - 1));
					scaleLevel = source.getNumMipmapLevels() - 1;
					return;
				}

				final RealPoint center = new RealPoint(3);
				final AffineTransform3D transform = new AffineTransform3D();
				source.getSourceTransform(timepoint, s, transform);
				transform.applyInverse(center, lastClick);

				if (firstTransform == null)
					firstTransform = transform;

				min = new long[]{
						Math.round(center.getDoublePosition(0) - 0.5 * w),
						Math.round(center.getDoublePosition(1) - 0.5 * h),
						Math.round(center.getDoublePosition(2) - 0.5 * d)};
				final long[] size = new long[]{w, h, d};

				IJ
						.log(
								String
										.format(
												"Cropping %s pixels at %s using scale level %d",
												Arrays.toString(size),
												Arrays.toString(min),
												s));

				final RandomAccessibleInterval<T> img = source.getSource(0, s);
				final RandomAccessible<T> imgExtended = Views.extendZero(img);
				final IntervalView<T> crop = Views.offsetInterval(imgExtended, min, size);

				channelsImages.add(crop);

				if (!single4DStack) {
					final ImagePlus imp = show(crop, "channel " + channel + " " + centerPosStr);
					setMetadata(imp, min, transform);
				}
			}

			if (single4DStack) {
				// FIXME: need to permute slices/channels. Swapping them in the
				// resulting ImagePlus produces wrong output
				final ImagePlus imp = ImageJFunctions
						.show(Views.permute(Views.stack(channelsImages), 2, 3), centerPosStr);
				if (firstTransform != null)
					setMetadata(imp, min, firstTransform);
			}

			viewer.requestRepaint();
		}

		/**
		 * Set image metadata after cropping.
		 * The sourceTransform must be scaling only.
		 *
		 * @param imp
		 *            the imageplus
		 * @param minPixels
		 *            the minimum pixel coordinate for cropping
		 * @param sourceTransform
		 *            the transformation from pixel to physical coordinates
		 */
		private void setMetadata(final ImagePlus imp, final long[] minPixels, final AffineTransform3D sourceTransform) {

			final double sx = sourceTransform.get(0, 0);
			final double sy = sourceTransform.get(1, 1);
			final double sz = sourceTransform.get(2, 2);

			imp.getCalibration().pixelWidth = sx;
			imp.getCalibration().pixelHeight = sy;
			imp.getCalibration().pixelDepth = sz;

			imp.getCalibration().xOrigin = sx * minPixels[0];
			imp.getCalibration().yOrigin = sy * minPixels[1];
			imp.getCalibration().zOrigin = sz * minPixels[2];
		}

		// Taken from ImageJFunctions. Modified to swap slices/channels for 3D
		// image (by default they mistakenly are nSlices=1 and nChannels=depth)
		// TODO: pull request with this fix if appropriate in general case?
		private ImagePlus show(final RandomAccessibleInterval<T> img, final String title) {

			final ImagePlus imp = ImageJFunctions.wrap(img, title);
			if (null == imp) {
				return null;
			}

			// Make sure that nSlices>1 and nChannels=nFrames=1 for 3D image
			final int[] possible3rdDim = new int[]{imp.getNChannels(), imp.getNSlices(), imp.getNFrames()};
			Arrays.sort(possible3rdDim);
			if (possible3rdDim[0] * possible3rdDim[1] == 1)
				imp.setDimensions(1, possible3rdDim[2], 1);

			imp.show();
			imp.getProcessor().resetMinAndMax();
			imp.updateAndRepaintWindow();

			return imp;
		}

		private void setCenterPointTextFieldsEnabled(final boolean enabled) {

			for (int i = 0; i < centerPointTextFields.size(); ++i) {
				final TextField tf = centerPointTextFields.get(i);
				tf.setEnabled(enabled);

				if (!enabled)
					tf.setText(Long.toString(centerPoint[i]));
			}
		}
	}
}
