/*
 * Copyright (c) 2014 "Kaazing Corporation," (www.kaazing.com)
 *
 * This file is part of Robot.
 *
 * Robot is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.kaazing.robot.control.handler;

import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;

import org.kaazing.robot.Robot;
import org.kaazing.robot.behavior.RobotCompletionFuture;
import org.kaazing.robot.control.AbortMessage;
import org.kaazing.robot.control.ErrorMessage;
import org.kaazing.robot.control.FinishedMessage;
import org.kaazing.robot.control.PrepareMessage;
import org.kaazing.robot.control.PreparedMessage;
import org.kaazing.robot.control.StartMessage;
import org.kaazing.robot.control.StartedMessage;
import org.kaazing.robot.lang.parser.ScriptParseException;

public class ControlServerHandler extends ControlUpstreamHandler {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ControlServerHandler.class);

    private Robot                       robot;
    private RobotCompletionFuture       scriptDoneFuture;

    private final ChannelFuture channelClosedFuture = Channels.future(null);

    // Note that this is more than just the channel close future. It's a future that means not only
    // that this channel has closed but it is a future that tells us when this obj has processed the closed event.
    public ChannelFuture getChannelClosedFuture() {
        return channelClosedFuture;
    }

    // public void completeShutDown(long timeout) throws TimeoutException {
    // long timeoutExpiredMs = System.currentTimeMillis() + timeout;
    // if (robot != null && !robot.isDestroyed()) {
    // boolean destroyed = robot.destroy();
    // while (!destroyed) {
    // Thread.yield();
    // destroyed = robot.destroy();
    // if (!destroyed && (System.currentTimeMillis() >= timeoutExpiredMs)) {
    // throw new TimeoutException("Could not destroy robot in " + timeout + " milliseconds.");
    // }
    // }
    // }
    // logger.info("Shutdown robot succesfully");
    // }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        logger.debug("Control channel closed");
        if(robot != null){
            robot.destroy();
        }
        channelClosedFuture.setSuccess();
        ctx.sendUpstream(e);
    }

    @Override
    public void prepareReceived(final ChannelHandlerContext ctx, MessageEvent evt) throws Exception {

        final PrepareMessage prepare = (PrepareMessage) evt.getMessage();

        if (logger.isDebugEnabled()) {
            logger.debug("preparing robot execution for script " + prepare.getScriptName());
        }

        robot = new Robot();

        ChannelFuture prepareFuture;
        try {
            // @formatter:off
            prepareFuture = robot.prepare(prepare.getExpectedScript());
            // @formatter:on
        }
        catch (Exception e) {
            sendErrorMessage(ctx, e, prepare.getScriptName());
            return;
        }

        prepareFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture f) {
                PreparedMessage prepared = new PreparedMessage();
                prepared.setCompatibilityKind(prepare.getCompatibilityKind());
                prepared.setScriptName(prepare.getScriptName());
                Channels.write(ctx, Channels.future(null), prepared);
            }
        });
    }

    @Override
    public void startReceived(final ChannelHandlerContext ctx, MessageEvent evt) throws Exception {

        final boolean infoDebugEnabled = logger.isDebugEnabled();
        final StartMessage start = (StartMessage) evt.getMessage();
        final String scriptName = start.getScriptName();

        if (infoDebugEnabled) {
            logger.debug("starting robot execution for script " + scriptName);
        }

        try {
            ChannelFuture startFuture = robot.start();
            startFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture f) {
                    final StartedMessage started = new StartedMessage();
                    started.setScriptName(scriptName);
                    Channels.write(ctx, Channels.future(null), started);
                }
            });
        }
        catch (Exception e) {
            sendErrorMessage(ctx, e, scriptName);
            return;
        }

        scriptDoneFuture = robot.getScriptCompleteFuture();

        scriptDoneFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture f) {
                String observedScript = scriptDoneFuture.getObservedScript();

                if (logger.isDebugEnabled()) {
                    logger.debug("Script " + scriptName + " completed");
                }
                FinishedMessage finished = new FinishedMessage();
                finished.setScriptName(scriptName);
                finished.setObservedScript(observedScript);
                Channels.write(ctx, Channels.future(null), finished);
            }
        });
    }

    @Override
    public void abortReceived(ChannelHandlerContext ctx, MessageEvent evt) throws Exception {
        AbortMessage abort = (AbortMessage) evt.getMessage();
        if (logger.isInfoEnabled()) {
            logger.debug("Aborting script " + abort.getScriptName());
        }
        robot.abort();
    }

    private void sendErrorMessage(ChannelHandlerContext ctx, Exception exception, String scriptName) {
        ErrorMessage error = new ErrorMessage();
        error.setDescription(exception.getMessage());
        error.setScriptName(scriptName);

        if (exception instanceof ScriptParseException) {
            if (logger.isDebugEnabled()) {
                logger.error("Caught exception trying to parse script. Sending error to client", exception);
            }
            else {
                logger.error("Caught exception trying to parse script. Sending error to client. Due to " + exception);
            }
            error.setSummary("Parse Error");
            Channels.write(ctx, Channels.future(null), error);
        } else {
            logger.error("Internal Error. Sending error to client", exception);
            error.setSummary("Internal Error");
	        Channels.write(ctx, Channels.future(null), error);	
		}
    }
}
